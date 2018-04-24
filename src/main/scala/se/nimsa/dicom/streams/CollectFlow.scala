package se.nimsa.dicom.streams

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import se.nimsa.dicom.DicomParts._
import se.nimsa.dicom._

object CollectFlow {

  case class DicomFragment(index: Int, bigEndian: Boolean, valueChunks: Seq[DicomValueChunk]) {
    def bytes: ByteString = valueChunks.map(_.bytes).fold(ByteString.empty)(_ ++ _)
  }

  case class CollectedElements(tag: String, characterSets: CharacterSets, elements: Seq[Element]) extends DicomPart {
    def bigEndian: Boolean = elements.headOption.exists(_.bigEndian)
    def bytes: ByteString = elements.map(_.bytes).reduce(_ ++ _)

    override def toString = s"${getClass.getSimpleName} tag=$tag elements=${elements.toList}"
  }

  /**
    * Collect the data elements specified by the input set of tags while buffering all elements of the stream. When the
    * stream has moved past the last element to collect, a CollectedElements element is emitted containing a list of
    * CollectedElement-parts with the collected information, followed by all buffered elements. Remaining elements in the
    * stream are immediately emitted downstream without buffering.
    *
    * This flow is used when there is a need to "look ahead" for certain information in the stream so that streamed
    * elements can be processed correctly according to this information. As an example, an implementation may have
    * different graph paths for different modalities and the modality must be known before any elements are processed.
    *
    * @param tags          tag paths of data elements to collect. Collection (and hence buffering) will end when the
    *                      stream moves past the highest tag number
    * @param elementsTag   a tag for the resulting CollectedElements to separate this from other such elements in the same
    *                      flow
    * @param maxBufferSize the maximum allowed size of the buffer (to avoid running out of memory). The flow will fail
    *                      if this limit is exceed. Set to 0 for an unlimited buffer size
    * @return A DicomPart Flow which will begin with a CollectedElements part followed by other parts in the flow
    */
  def collectFlow(tags: Set[TagPath], elementsTag: String, maxBufferSize: Int = 1000000): Flow[DicomPart, DicomPart, NotUsed] = {
    val maxTag = if (tags.isEmpty) 0 else tags.map(_.toList.head.tag).max
    val tagCondition = (tagPath: TagPath) => tags.exists(tagPath.startsWithSuperPath)
    val stopCondition = if (tags.isEmpty)
      (_: TagPath) => true
    else
      (tagPath: TagPath) => tagPath.isRoot && tagPath.tag > maxTag
    collectFlow(tagCondition, stopCondition, elementsTag, maxBufferSize)
  }

  /**
    * Collect data elements whenever the input tag condition yields `true` while buffering all elements of the stream. When
    * the stop condition yields `true`, a CollectedElements is emitted containing a list of
    * CollectedElement objects with the collected information, followed by all buffered parts. Remaining elements in the
    * stream are immediately emitted downstream without buffering.
    *
    * This flow is used when there is a need to "look ahead" for certain information in the stream so that streamed
    * elements can be processed correctly according to this information. As an example, an implementation may have
    * different graph paths for different modalities and the modality must be known before any elements are processed.
    *
    * @param tagCondition  function determining the condition(s) for which elements are collected
    * @param stopCondition function determining the condition for when collection should stop and elements are emitted
    * @param label         a label for the resulting CollectedElements to separate this from other such elements in the
    *                      same flow
    * @param maxBufferSize the maximum allowed size of the buffer (to avoid running out of memory). The flow will fail
    *                      if this limit is exceed. Set to 0 for an unlimited buffer size
    * @return A DicomPart Flow which will begin with a CollectedElements followed by the input parts
    */
  def collectFlow(tagCondition: TagPath => Boolean, stopCondition: TagPath => Boolean, label: String, maxBufferSize: Int): Flow[DicomPart, DicomPart, NotUsed] =
    DicomFlowFactory.create(new DeferToPartFlow[DicomPart] with TagPathTracking[DicomPart] with EndEvent[DicomPart] {

      var reachedEnd = false
      var currentBufferSize = 0
      var currentElement: Option[Element] = None
      var buffer: List[DicomPart] = Nil
      var characterSets: CharacterSets = CharacterSets.defaultOnly
      var elements: List[Element] = Nil

      def elementsAndBuffer(): List[DicomPart] = {
        val parts = CollectedElements(label, characterSets, elements) :: buffer

        reachedEnd = true
        buffer = Nil
        currentBufferSize = 0

        parts
      }

      override def onEnd(): List[DicomPart] =
        if (reachedEnd)
          Nil
        else
          elementsAndBuffer()

      override def onPart(part: DicomPart): List[DicomPart] = {
        if (reachedEnd)
          part :: Nil
        else {
          if (maxBufferSize > 0 && currentBufferSize > maxBufferSize)
            throw new DicomStreamException("Error collecting elements: max buffer size exceeded")

          buffer = buffer :+ part
          currentBufferSize = currentBufferSize + part.bytes.size

          part match {
            case _: DicomHeader if tagPath.exists(stopCondition) =>
              elementsAndBuffer()

            case header: DicomHeader if tagPath.exists(tagCondition) || header.tag == Tag.SpecificCharacterSet =>
              currentElement = tagPath.map(tp => Element(tp.tag, header.bigEndian, header.vr, header.explicitVR, header.length, ByteString.empty))
              Nil

            case _: DicomHeader =>
              currentElement = None
              Nil

            case valueChunk: DicomValueChunk =>

              currentElement match {
                case Some(element) =>
                  val updatedElement = element.copy(value = element.value ++ valueChunk.bytes)
                  currentElement = Some(updatedElement)
                  if (valueChunk.last) {
                    if (updatedElement.tag == Tag.SpecificCharacterSet)
                      characterSets = CharacterSets(updatedElement.bytes)
                    if (tagPath.exists(tagCondition))
                      elements = elements :+ updatedElement
                    currentElement = None
                  }
                  Nil

                case None => Nil
              }

            case _ => Nil
          }
        }
      }
    })


}
