package ml.combust.mleap.executor

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.testkit.TestKit
import ml.combust.mleap.runtime.frame.Row
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, FunSpecLike}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Try

class MleapExecutorSpec extends TestKit(ActorSystem("MleapExecutorSpec"))
  with FunSpecLike
  with BeforeAndAfterAll
  with ScalaFutures {

  private val executor = MleapExecutor(system)
  private val frame = TestUtil.frame
  private implicit val materializer: Materializer = ActorMaterializer()(system)

  Await.result(
    executor.loadModel(LoadModelRequest(
      modelName = "rf_model",
      uri = TestUtil.rfUri,
      config = ModelConfig(
        memoryTimeout = 15.minutes,
        diskTimeout = 15.minutes
      )
    ))(10.seconds), 10.seconds)

  val rowStreamConfig = StreamConfig(
    idleTimeout = 15.minutes,
    transformTimeout = 15.minutes,
    parallelism = 4,
    bufferSize = 1024
  )
  val spec = RowStreamSpec(frame.schema)

  Await.result(
    executor.createRowStream(CreateRowStreamRequest(
      "rf_model",
      "stream1",
      rowStreamConfig,
      spec
    ))(10.seconds), 10.seconds)

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system, 5.seconds, verifySystemShutdown = true)
  }

  describe("transforming a leap frame") {
    it("transforms the leap frame") {

      val result = executor.transform(TransformFrameRequest("rf_model", frame))(5.second).
        flatMap(Future.fromTry)

      whenReady(result, Timeout(5.seconds)) {
        transformed => assert(transformed.schema.hasField("price_prediction"))
      }
    }
  }

  describe("get bundle meta") {
    it("retrieves info for bundle") {
      val result = executor.getBundleMeta(GetBundleMetaRequest("rf_model"))(5.second)
      whenReady(result, Timeout(5.seconds)) {
        info => assert(info.info.name == "pipeline_8d2ca5c4dd62")
      }
    }
  }

  describe("transform stream") {
    it("transforms rows in a stream") {
      val rowsSource = Source.fromIterator(() => frame.dataset.iterator.map(row => StreamTransformRowRequest(row)).zipWithIndex)
      val rowsSink = Sink.seq[(Try[Option[Row]], Int)]
      val testFlow = Flow.fromSinkAndSourceMat(rowsSink, rowsSource)(Keep.left)

      val config = FlowConfig(
        idleTimeout = 15.minutes,
        transformTimeout = 15.minutes,
        parallelism = 4
      )

      val (done, transformedRows) = executor.rowFlow(CreateRowFlowRequest("rf_model", "stream1", config))(10.seconds).
        watchTermination()(Keep.right).
        joinMat(testFlow)(Keep.both).
        run()

      whenReady(transformedRows, Timeout(10.seconds)) {
        rows =>
          assert(rows.size == 1)

          for((row, _) <- rows) {
            assert(row.isSuccess)
          }
      }

      done.isReadyWithin(1.second)
    }
  }
}
