package akka.remote

import akka.remote.FailureDetector.Clock
import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import com.typesafe.config.Config
import akka.event.EventStream

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
 * @param scalingFactor A scaling factor to prevent the failure detector to overestimate the probability of failures
 *   particularly in the case of increasing network latency times
 * @param clock The clock, returning current time in milliseconds, but can be faked for testing
 *   purposes. It is only used for measuring intervals (duration).
 */
class NewAdaptiveAccrualFailureDetector(
  val threshold:                Double,
  val maxSampleSize:            Int,
  val scalingFactor:            Double,
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
      Some(ev))

  require(threshold > 0.0, "failure-detector.threshold must be > 0")
  require(maxSampleSize > 0, "failure-detector.max-sample-size must be > 0")
  require(scalingFactor > 0, "failure-detector.scaling-factor must be > 0")

  private val firstHeartbeat: HeartbeatHistory = HeartbeatHistory(maxSampleSize)

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
        // according to the algorithm do not add any initial history
        firstHeartbeat
      case Some(freshnessPoint) ⇒
        // this is a known connection
        val tΔ = timestamp - freshnessPoint
        oldState.history :+ tΔ
    }

    val newState = oldState.copy(history = newHistory, freshnessPoint = Some(timestamp)) // record new timestamp

    // if we won the race then update else try again
    if (!state.compareAndSet(oldState, newState)) heartbeat() // recur
  }

  /**
   * The suspicion level of the accrual failure detector (expressed as probability).
   *
   * If a connection does not have any records in failure detector then it is considered healthy.
   */
  def suspicion: Double = suspicion(clock())

  private def suspicion(timestamp: Long): Double = {
    val oldState = state.get
    val freshnessPoint = oldState.freshnessPoint

    if (freshnessPoint.isEmpty || oldState.history.intervals.isEmpty) 0.0 // treat unmanaged connections, e.g. with zero heartbeats, as healthy connections
    else {
      val tΔ = timestamp - freshnessPoint.get
      val S = oldState.history.intervals
      val SLength = S.length
      val StΔLength = S.count(_ <=  tΔ * scalingFactor)

      StΔLength.toDouble / SLength.toDouble
    }
  }

}

