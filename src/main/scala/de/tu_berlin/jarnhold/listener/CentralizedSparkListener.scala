package de.tu_berlin.jarnhold.listener

import de.tu_berlin.jarnhold.listener.SafeDivision.saveDivision
import org.apache.commons.lang3.time.StopWatch
import org.apache.spark.scheduler._
import org.apache.spark.{SparkConf, SparkContext}
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
 * Create a spark listener emitting spark events via a ZeroMQ client.
 * @param sparkConf Is injected automatically to listener when added via "spark.extraListeners".
 */
class CentralizedSparkListener(sparkConf: SparkConf) extends SparkListener {

  private val logger: Logger = LoggerFactory.getLogger(classOf[CentralizedSparkListener])
  logger.info("Initializing CentralizedSparkListener")

  // Listener configuration
  private val isAdaptive: Boolean = sparkConf.getBoolean("spark.customExtraListener.isAdaptive", defaultValue = true)
  private val bridgeServiceAddress: String = sparkConf.get("spark.customExtraListener.bridgeServiceAddress")
  private val active: Boolean = this.isAdaptive
  private val executorRequestTimeout: Integer = Option(System.getenv("EXECUTOR_REQUEST_TIMEOUT")).getOrElse("10000").toInt
  private val executorRequestStopWatch: StopWatch = StopWatch.create()

  // Setup communication
  private val zeroMQClient = new ZeroMQClient(bridgeServiceAddress)

  // Application parameters
  private val applicationId: String = sparkConf.getAppId
  private val appSignature: String = sparkConf.get("spark.app.name")
  private var appEventId: String = _
  private var appStartTime: Long = _
  private var sparkContext: SparkContext = _
  private var initialScaleOut: Integer = _
  private val currentScaleOut = new AtomicInteger(0)

  // scale-out, time of measurement, total time
  private val scaleOutBuffer: ListBuffer[(Int, Long)] = ListBuffer()
  // stage monitoring
  private val stageInfoMap: StageInfoMap = new StageInfoMap()

  /**
   * For testing purposes.
   */
  def this(sparkConf: SparkConf, sparkContext: SparkContext) = {
    this(sparkConf)
    this.sparkContext = sparkContext
  }

  override def onApplicationStart(applicationStart: SparkListenerApplicationStart): Unit = {

    checkConfigurations(this.sparkConf)
    // calling this on AppStart would cause a second SparkContext creation, which would cause an error in the Spark app
    this.sparkContext = SparkContext.getOrCreate(this.sparkConf)

    val response = sendAppStartMessage()
    this.appEventId = response.app_event_id
    this.appStartTime = appStartTime
    this.initialScaleOut = response.recommended_scale_out

    logger.info(
      "SparkContext successfully registered in CentralizedSparkListener and executor recommendation received. "
        + "Requesting {} executors", this.initialScaleOut
    )
    this.sparkContext.requestTotalExecutors(this.initialScaleOut, 0, Map[String, Int]())
    this.executorRequestStopWatch.start()
  }

  override def onJobStart(jobStart: SparkListenerJobStart): Unit = {
    val jobId = jobStart.jobId
    if (isInitialJobOfSparkApplication(jobId)) {
      // because the number of requested executors might differ from the actual amount,
      // we verify how many executors are actually present
      //
      // additionally, although this is an edge case, the first Job might start when the executors are not ready yet
      while (this.currentScaleOut.get() == 0) {
        setActualScaleOut()
      }
    }
    this.stageInfoMap.addJob(jobStart)
    val response = sendJobStartMessage(jobStart.jobId, jobStart.time)
    adjustScaleOutIfNecessary(response)
  }

  override def onStageSubmitted(stageSubmitted: SparkListenerStageSubmitted): Unit = {
    val scaleOut = this.currentScaleOut.get()
    this.stageInfoMap.addStageSubmit(scaleOut, stageSubmitted)
  }

  override def onStageCompleted(stageCompleted: SparkListenerStageCompleted): Unit = {
   val rescalingTimeRatio = computeRescalingTimeRatio(
      stageCompleted.stageInfo.submissionTime.getOrElse(0L),
      stageCompleted.stageInfo.completionTime.getOrElse(0L)
    )
    val scaleOut = this.currentScaleOut.get()
    this.stageInfoMap.addStageComplete(scaleOut, rescalingTimeRatio, stageCompleted)
  }

  override def onJobEnd(jobEnd: SparkListenerJobEnd): Unit = {
    val jobId = jobEnd.jobId
    val jobDuration = jobEnd.time
    val rescalingTimeRatio: Double = computeRescalingTimeRatio(this.appStartTime, jobEnd.time)
    val stages = this.stageInfoMap.getStages(jobId)
    val response = sendJobEndMessage(jobId, jobDuration, rescalingTimeRatio, stages)
    adjustScaleOutIfNecessary(response)
  }

  override def onApplicationEnd(applicationEnd: SparkListenerApplicationEnd): Unit = {
    this.sendAppEndMessage(applicationEnd.time)
    this.zeroMQClient.close()
  }

  override def onExecutorAdded(executorAdded: SparkListenerExecutorAdded): Unit = {
    handleScaleOutMonitoring(Option(executorAdded.time), executorAdded.executorInfo.executorHost)
  }

  override def onExecutorRemoved(executorRemoved: SparkListenerExecutorRemoved): Unit = {
    handleScaleOutMonitoring(Option(executorRemoved.time), "NO_HOST")
  }

  private def adjustScaleOutIfNecessary(response: ResponseMessage): Unit = {
    val recommendedScaleOut = response.recommended_scale_out
    if (recommendedScaleOut != this.currentScaleOut.get()) {

      this.executorRequestStopWatch.stop()
      // for fast jobs, we don't want to request different amounts of executors too often
      if (this.executorRequestStopWatch.getTime(TimeUnit.MILLISECONDS) < this.executorRequestTimeout) {
        return
      }

      logger.info(s"Requesting scale-out of $recommendedScaleOut after next job...")
      val requestResult = this.sparkContext.requestTotalExecutors(recommendedScaleOut, 0, Map[String, Int]())
      logger.info("Request acknowledged? => " + requestResult.toString)
      this.executorRequestStopWatch.start()
    }
  }

  private def handleScaleOutMonitoring(executorActionTime: Option[Long], executorHost: String): Unit = {
    synchronized {
      // the executors might be added before actual application start => there won't be a SparkContext yet in this case,
      // thus also no current scale out to monitor yet
      if (!this.active || this.sparkContext == null) {
        return
      }
      // An executor was removed? Else, an executor was added
      if (executorHost == "NO_HOST") {
        this.currentScaleOut.decrementAndGet()
      } else {
        this.currentScaleOut.incrementAndGet()
      }

      logger.info(s"Current number of executors: ${currentScaleOut.get()}.")
      if (executorActionTime.isDefined) {
        scaleOutBuffer.append((currentScaleOut.get(), executorActionTime.get))
      }
    }
  }

  /**
   * Ensure spark context is set before calling.
   */
  private def setActualScaleOut(): Unit = {
    synchronized {
      val allExecutors = this.sparkContext.getExecutorMemoryStatus.toSeq.map(_._1)
      val driverHost: String = getDriverHost
      val scaleOut = allExecutors
        .filter(!_.split(":")(0).equals(driverHost))
        .toList
        .length
      this.currentScaleOut.set(scaleOut)
    }
  }

  private def checkConfigurations(sparkConf: SparkConf): Unit = {
    logger.info("Current spark conf" + sparkConf.toDebugString)
    for (param <- SpecBuilder.requiredSparkConfParams) {
      if (!sparkConf.contains(param)) {
        throw new IllegalArgumentException(s"Parameter $param is not specified in the environment!")
      }
    }
  }

  private def computeRescalingTimeRatio(startTime: Long, endTime: Long): Double = {

    val scaleOutList: List[(Int, Long)] = scaleOutBuffer.toList

    val dividend: Long = scaleOutList
      .sortBy(_._2)
      .zipWithIndex.map { case (tup, idx) => (tup._1, tup._2, Try(scaleOutList(idx + 1)._2 - tup._2).getOrElse(0L)) }
      .filter(e => e._2 + e._3 >= startTime && e._2 <= endTime)
      .drop(1) // drop first element => is respective start scale-out
      .dropRight(1) // drop last element => is respective end scale-out
      .map(e => {
        val startTimeScaleOut: Long = e._2
        var endTimeScaleOut: Long = e._2 + e._3
        if (e._3 == 0L)
          endTimeScaleOut = endTime

        val intervalStartTime: Long = Math.min(Math.max(startTime, startTimeScaleOut), endTime)
        val intervalEndTime: Long = Math.max(startTime, Math.min(endTime, endTimeScaleOut))

        intervalEndTime - intervalStartTime
      })
      .sum

    saveDivision(dividend, endTime - startTime)
  }

  private def sendJobStartMessage(jobId: Int, appTime: Long): ResponseMessage = {
    val message = JobStartMessage(
      app_event_id = this.appEventId,
      app_time = appTime,
      job_id = jobId,
      num_executors = this.currentScaleOut.get(),
    )
    this.zeroMQClient.sendMessage(EventType.JOB_START, message)
  }

  private def sendJobEndMessage(jobId: Int, appTime: Long, rescalingTimeRatio: Double, stages: Map[String, Stage]): ResponseMessage = {
    val message = JobEndMessage(
      app_event_id = this.appEventId,
      app_time = appTime,
      job_id = jobId,
      num_executors = this.currentScaleOut.get(),
      rescaling_time_ratio = rescalingTimeRatio,
      stages = stages
    )
    this.zeroMQClient.sendMessage(EventType.JOB_END, message)
  }

  private def sendAppStartMessage(): ResponseMessage = {
    val specBuilder = new SpecBuilder(this.sparkConf)
    val message = AppStartMessage(
      application_id = this.applicationId,
      app_name = this.appSignature,
      app_time = System.currentTimeMillis(),
      is_adaptive = this.isAdaptive,
      app_specs = specBuilder.buildAppSpecs(),
      driver_specs = specBuilder.buildDriverSpecs(),
      executor_specs = specBuilder.buildExecutorSpecs(),
      environment_specs = specBuilder.buildEnvironmentSpecs(),
    )
    this.zeroMQClient.sendMessage(EventType.APPLICATION_START, message)
  }

  private def sendAppEndMessage(appTime: Long): ResponseMessage = {
    val message = AppEndMessage(
      app_event_id = this.appEventId,
      app_time = appTime,
      num_executors = this.currentScaleOut.get()
    )
    this.zeroMQClient.sendMessage(EventType.APPLICATION_END, message)
  }

  /**
   * Ensure spark context is set before calling.
   */
  private def getDriverHost = {
    this.sparkContext.getConf.get("spark.driver.host")
  }

  private def isInitialJobOfSparkApplication(jobId: Int) = {
    jobId == 0
  }
}
