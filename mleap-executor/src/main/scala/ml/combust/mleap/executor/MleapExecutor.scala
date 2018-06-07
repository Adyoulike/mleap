package ml.combust.mleap.executor

import java.net.URI
import java.util.concurrent.{ExecutorService, Executors}

import akka.NotUsed
import akka.actor.{ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.stream.scaladsl.Flow
import com.typesafe.config.{Config, ConfigFactory}
import ml.combust.mleap.executor.repository.{FileRepository, Repository, RepositoryBundleLoader}
import ml.combust.mleap.executor.service.LocalTransformService
import ml.combust.mleap.executor.stream.TransformStream
import ml.combust.mleap.runtime.frame.{DefaultLeapFrame, Row, RowTransformer}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object MleapExecutor extends ExtensionId[MleapExecutor] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): MleapExecutor = {
    new MleapExecutor(ConfigFactory.load().getConfig("ml.combust.mleap.executor"))(system)
  }

  override def lookup(): ExtensionId[_ <: Extension] = MleapExecutor
}

class MleapExecutor(tConfig: Config)
                   (implicit system: ExtendedActorSystem) extends Extension {
  import system.dispatcher

  private val repository: Repository = Repository.fromConfig(tConfig.getConfig("repository"))
  private val loader: RepositoryBundleLoader = new RepositoryBundleLoader(repository, diskEc)
  private val transformService: LocalTransformService = new LocalTransformService(loader)(system.dispatcher, system)

  system.registerOnTermination {
    diskThreadPool.shutdown()
  }

  def getBundleMeta(uri: URI)
                   (implicit timeout: FiniteDuration): Future[BundleMeta] = {
    transformService.getBundleMeta(uri)
  }

  def getBundleMeta(uri: URI, timeout: Int): Future[BundleMeta] = {
    transformService.getBundleMeta(uri, timeout)
  }

  def transform(uri: URI, request: TransformFrameRequest)
               (implicit timeout: FiniteDuration): Future[DefaultLeapFrame] = {
    transformService.transform(uri, request)
  }

  def transform(uri: URI,
                request: TransformFrameRequest,
                timeout: Int): Future[DefaultLeapFrame] = {
    transformService.transform(uri, request, timeout)
  }

  def frameFlow[Tag](uri: URI,
                     parallelism: Int = TransformStream.DEFAULT_PARALLELISM)
                    (implicit timeout: FiniteDuration): Flow[(TransformFrameRequest, Tag), (Try[DefaultLeapFrame], Tag), NotUsed] = {
    transformService.frameFlow(uri, parallelism)
  }

  def rowFlow[Tag](uri: URI,
                   spec: StreamRowSpec,
                   parallelism: Int = TransformStream.DEFAULT_PARALLELISM)
                  (implicit timeout: FiniteDuration): Flow[(Try[Row], Tag), (Try[Option[Row]], Tag), Future[RowTransformer]] = {
    transformService.rowFlow(uri, spec, parallelism)
  }
}
