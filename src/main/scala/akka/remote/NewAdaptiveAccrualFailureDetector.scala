package akka.remote

import akka.event.Logging.Warning
import akka.remote.FailureDetector.Clock
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import com.typesafe.config.Config
import akka.event.EventStream
import akka.util.Helpers.ConfigOps

/**
 * Implementation of 'A New Adaptive Accrual Failure Detector for Dependable Distributed Systems' by Satzget al. as defined in their paper:
 * [https://pdfs.semanticscholar.org/8805/d522cd6cef723aae55595f918e09914e4316.pdf]
 *
 * The idea of this failure detector is to predict the arrival time of the next heartbeat based on
 * the history of inter-arrival times between heartbeats. The algorithm approximates the cumulative distribution function (CDF)
 * of inter-arrival times of the heartbeat messages.
 *
 * The suspicion value of a failure is calculated as follows:
 *
 * {{{
 * P = |StΔ| / |S|
 * }}}
 *
 * where:
 * - S is the list of historical inter-arrival times of heartbeats
 * - StΔ the list of inter-arrival times that are smaller or equal to tΔ
 * - tΔ = previous heartbeat timestamp - current heartbeat timestamp
 *
 * @param threshold A low threshold is prone to generate many wrong suspicions but ensures a quick detection in the event
 *   of a real crash. Conversely, a high threshold generates fewer mistakes but needs more time to detect
 *   actual crashes
 * @param maxSampleSize Number of samples to use for calculation of mean and standard deviation of
 *   inter-arrival times.
 * @param acceptableHeartbeatPause Duration corresponding to number of potentially lost/delayed
 *   heartbeats that will be accepted before considering it to be an anomaly.
 *   This margin is important to be able to survive sudden, occasional, pauses in heartbeat
 *   arrivals, due to for example garbage collect or network drop.
 * @param firstHeartbeatEstimate Bootstrap the stats with heartbeats that corresponds to
 *   to this duration, with a with rather high standard deviation (since environment is unknown
 *   in the beginning)
 * @param clock The clock, returning current time in milliseconds, but can be faked for testing
 *   purposes. It is only used for measuring intervals (duration).
 */
class NewAdaptiveAccrualFailureDetector(
  val threshold:                Double,
  val maxSampleSize:            Int,
  val scalingFactor:            Double,
  val acceptableHeartbeatPause: FiniteDuration,
  val firstHeartbeatEstimate:   FiniteDuration,
  eventStream:                  Option[EventStream])(
  implicit
  clock: Clock) extends FailureDetector {

  /**
   * Constructor that reads parameters from config.
   * Expecting config properties named `threshold`, `max-sample-size`,
   * `min-std-deviation`, `acceptable-heartbeat-pause` and
   * `heartbeat-interval`.
   */
  def this(config: Config, ev: EventStream) =
    this(
      threshold = config.getDouble("threshold"),
      maxSampleSize = config.getInt("max-sample-size"),
      scalingFactor = config.getDouble("scaling-factor"),
      acceptableHeartbeatPause = config.getMillisDuration("acceptable-heartbeat-pause"),
      firstHeartbeatEstimate = config.getMillisDuration("heartbeat-interval"),
      Some(ev))

  require(threshold > 0.0, "failure-detector.threshold must be > 0")
  require(maxSampleSize > 0, "failure-detector.max-sample-size must be > 0")
  require(scalingFactor > 1, "failure-detector.scaling-factor must be > 1")
  require(acceptableHeartbeatPause >= Duration.Zero, "failure-detector.acceptable-heartbeat-pause must be >= 0")
  require(firstHeartbeatEstimate > Duration.Zero, "failure-detector.heartbeat-interval must be > 0")

  // guess statistics for first heartbeat,
  // important so that connections with only one heartbeat becomes unavailable
  private val firstHeartbeat: HeartbeatHistory = {
    // bootstrap with 2 entries with rather high standard deviation
    val mean = firstHeartbeatEstimate.toMillis
    val stdDeviation = mean / 4
    HeartbeatHistory(maxSampleSize) :+ (mean - stdDeviation) :+ (mean + stdDeviation)
  }

  private val acceptableHeartbeatPauseMillis = acceptableHeartbeatPause.toMillis

  /**
   * Implement using optimistic lockless concurrency, all state is represented
   * by this immutable case class and managed by an AtomicReference.
   */
  private final case class State(history: HeartbeatHistory, freshnessPoint: Option[Long])

  private val state = new AtomicReference[State](State(history = firstHeartbeat, freshnessPoint = None))

  override def isAvailable: Boolean = isAvailable(clock())

  private def isAvailable(timestamp: Long): Boolean = suspicion(timestamp) < threshold

  override def isMonitoring: Boolean = state.get.freshnessPoint.nonEmpty

  @tailrec
  final override def heartbeat(): Unit = {

    val timestamp = clock()
    val oldState = state.get

    val newHistory = oldState.freshnessPoint match {
      case None ⇒
        // this is heartbeat from a new resource
        // add starter records for this new resource
        firstHeartbeat
      case Some(latestTimestamp) ⇒
        // this is a known connection
        val interval = timestamp - latestTimestamp
        // don't use the first heartbeat after failure for the history, since a long pause will skew the stats
        if (isAvailable(timestamp)) {
          if (interval >= (acceptableHeartbeatPauseMillis / 2) && eventStream.isDefined)
            eventStream.get.publish(Warning(this.toString, getClass, s"heartbeat interval is growing too large: $interval millis"))
          oldState.history :+ interval
        } else oldState.history
    }

    val newState = oldState.copy(history = newHistory, freshnessPoint = Some(timestamp)) // record new timestamp

    // if we won the race then update else try again
    if (!state.compareAndSet(oldState, newState)) heartbeat() // recur
  }

  /**
   * The suspicion level of the accrual failure detector (expressed as percentage).
   *
   * If a connection does not have any records in failure detector then it is
   * considered healthy.
   */
  def suspicion: Double = suspicion(clock())

  private def suspicion(timestamp: Long): Double = {
    val oldState = state.get
    val freshnessPoint = oldState.freshnessPoint

    if (freshnessPoint.isEmpty) 0.0 // treat unmanaged connections, e.g. with zero heartbeats, as healthy connections
    else {
      val timeDiff = timestamp - freshnessPoint.get

      val history = oldState.history

      val intervalLength = history.intervals.length
      val intervalTimeDeltaLength = history.intervals.count(_ <=  timeDiff * scalingFactor)

      intervalTimeDeltaLength.toDouble / intervalLength.toDouble
    }
  }

}

