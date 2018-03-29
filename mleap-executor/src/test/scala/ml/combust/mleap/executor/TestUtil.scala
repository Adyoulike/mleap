package ml.combust.mleap.executor

import java.io.File
import java.net.URI

import ml.combust.bundle.BundleFile
import ml.combust.bundle.dsl.Bundle
import ml.combust.mleap.core.types.StructType
import ml.combust.mleap.runtime.frame.{DefaultLeapFrame, Transformer}
import ml.combust.mleap.runtime.serialization.FrameReader
import resource.managed
import ml.combust.mleap.runtime.MleapSupport._

object TestUtil {
  lazy val rfUri: URI = {
    getClass.getClassLoader.getResource("models/airbnb.model.rf.zip").toURI
  }

  lazy val lrUri: URI = {
    getClass.getClassLoader.getResource("models/airbnb.model.lr.zip").toURI
  }

  lazy val faultyFrame: DefaultLeapFrame = {
    DefaultLeapFrame(StructType(), Seq())
  }

  lazy val frame: DefaultLeapFrame = {
    FrameReader().read(new File(getClass.getClassLoader.getResource("leap_frame/frame.airbnb.json").getFile)).get
  }

  lazy val lrBundle : Bundle[Transformer] = {
    val bundle = (for(bundle <- managed(BundleFile(new File(lrUri.getPath)))) yield {
      bundle.loadMleapBundle().get
    }).tried.get

    bundle
  }
}
