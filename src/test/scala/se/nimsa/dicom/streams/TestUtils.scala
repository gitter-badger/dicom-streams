package se.nimsa.dicom.streams

import java.util.zip.Deflater

import akka.stream.testkit.TestSubscriber
import akka.util.ByteString
import se.nimsa.dicom.data.DicomParts._
import se.nimsa.dicom.data.Elements._
import se.nimsa.dicom.data.VR.VR
import se.nimsa.dicom.data._

object TestUtils {

  def deflate(bytes: ByteString, gzip: Boolean = false): ByteString = {
    val deflater = if (gzip) new Deflater() else new Deflater(-1, true)
    deflater.setInput(bytes.toArray)
    val buffer = new Array[Byte](bytes.length)
    var out = ByteString.empty
    var n = 1
    while (n > 0) {
      n = deflater.deflate(buffer, 0, buffer.length, Deflater.FULL_FLUSH)
      out = out ++ ByteString.fromArray(buffer.take(n))
    }
    out
  }

  case class TestPart(id: String) extends DicomPart {
    override def bigEndian: Boolean = false
    override def bytes: ByteString = ByteString.empty
    override def toString = s"${getClass.getSimpleName}: $id"
  }

  type PartProbe = TestSubscriber.Probe[DicomPart]

  implicit class DicomPartProbe(probe: PartProbe) {
    def expectPreamble(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: PreamblePart => true
        case p => throw new RuntimeException(s"Expected DicomPreamble, got $p")
      }

    def expectValueChunk(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: ValueChunk => true
        case p => throw new RuntimeException(s"Expected DicomValueChunk, got $p")
      }

    def expectValueChunk(length: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case chunk: ValueChunk if chunk.bytes.length == length => true
        case p => throw new RuntimeException(s"Expected DicomValueChunk with length = $length, got $p")
      }

    def expectValueChunk(bytes: ByteString): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case chunk: ValueChunk if chunk.bytes == bytes => true
        case chunk: ValueChunk => throw new RuntimeException(s"Expected DicomValueChunk with bytes = $bytes, got $chunk with bytes ${chunk.bytes}")
        case p => throw new RuntimeException(s"Expected DicomValueChunk with bytes = $bytes, got $p")
      }

    def expectItem(index: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case item: ItemPart if item.index == index => true
        case p => throw new RuntimeException(s"Expected DicomItem with index = $index, got $p")
      }

    def expectItem(index: Int, length: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case item: ItemPart if item.index == index && item.length == length => true
        case p => throw new RuntimeException(s"Expected DicomItem with index = $index and length $length, got $p")
      }

    def expectItemDelimitation(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: ItemDelimitationPart => true
        case p => throw new RuntimeException(s"Expected DicomItemDelimitation, got $p")
      }

    def expectFragments(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: FragmentsPart => true
        case p => throw new RuntimeException(s"Expected DicomFragments, got $p")
      }

    def expectFragment(index: Int, length: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case item: ItemPart if item.index == index && item.length == length => true
        case p => throw new RuntimeException(s"Expected DicomFragment with index = $index and length $length, got $p")
      }

    def expectFragmentsDelimitation(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: SequenceDelimitationPart => true
        case p => throw new RuntimeException(s"Expected DicomFragmentsDelimitation, got $p")
      }

    def expectHeader(tag: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case h: HeaderPart if h.tag == tag => true
        case p => throw new RuntimeException(s"Expected DicomHeader with tag = ${tagToString(tag)}, got $p")
      }

    def expectHeader(tag: Int, vr: VR, length: Long): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case h: HeaderPart if h.tag == tag && h.vr == vr && h.length == length => true
        case p => throw new RuntimeException(s"Expected DicomHeader with tag = ${tagToString(tag)}, VR = $vr and length = $length, got $p")
      }

    def expectSequence(tag: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case h: SequencePart if h.tag == tag => true
        case p => throw new RuntimeException(s"Expected DicomSequence with tag = ${tagToString(tag)}, got $p")
      }

    def expectSequence(tag: Int, length: Int): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case h: SequencePart if h.tag == tag && h.length == length => true
        case p => throw new RuntimeException(s"Expected DicomSequence with tag = ${tagToString(tag)} and length = $length, got $p")
      }

    def expectSequenceDelimitation(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: SequenceDelimitationPart => true
        case p => throw new RuntimeException(s"Expected DicomSequenceDelimitation, got $p")
      }

    def expectUnknownPart(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: UnknownPart => true
        case p => throw new RuntimeException(s"Expected UnkownPart, got $p")
      }

    def expectDeflatedChunk(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: DeflatedChunk => true
        case p => throw new RuntimeException(s"Expected DicomDeflatedChunk, got $p")
      }

    def expectDicomComplete(): PartProbe = probe
      .request(1)
      .expectComplete()

    def expectDicomError(): Throwable = probe
      .request(1)
      .expectError()

    def expectElements(): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case _: ElementsPart => true
        case p => throw new RuntimeException(s"Expected ElementsPart, got $p")
      }

    def expectElements(elementsPart: ElementsPart): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case p: ElementsPart if p == elementsPart => true
        case p => throw new RuntimeException(s"Expected ElementsPart with part = $elementsPart, got $p")
      }

    def expectTestPart(id: String): PartProbe = probe
      .request(1)
      .expectNextChainingPF {
        case a: TestPart if a.id == id => true
        case p => throw new RuntimeException(s"Expected TestPart with id = $id, got $p")
      }
  }

  type ElementProbe = TestSubscriber.Probe[Element]

  implicit class DicomElementProbe(probe: ElementProbe) {
    def expectElement(tag: Int): ElementProbe = probe
      .request(1)
      .expectNextChainingPF {
        case e: ValueElement if e.tag == tag => true
        case e: SequenceElement if e.tag == tag => true
        case e: FragmentsElement if e.tag == tag => true
        case p => throw new RuntimeException(s"Expected Element with tag $tag, got $p")
      }

    def expectElement(tag: Int, value: ByteString): ElementProbe = probe
      .request(1)
      .expectNextChainingPF {
        case e: ValueElement if e.tag == tag && e.value.bytes == value => true
        case p => throw new RuntimeException(s"Expected Element with tag $tag and value $value, got $p")
      }

    def expectFragments(tag: Int): ElementProbe = probe
      .request(1)
      .expectNextChainingPF {
        case e: FragmentsElement if e.tag == tag => true
        case p => throw new RuntimeException(s"Expected Fragments with tag $tag, got $p")
      }

    def expectFragment(length: Long): ElementProbe = probe
      .request(1)
      .expectNextChainingPF {
        case e: FragmentElement if e.length == length => true
        case p => throw new RuntimeException(s"Expected Fragment with length $length, got $p")
      }

    def expectSequenceDelimitation(): ElementProbe = probe
      .request(1)
      .expectNextChainingPF {
        case e: SequenceDelimitationElement => true
        case p => throw new RuntimeException(s"Expected SequencdDelimitationElement, got $p")
      }

    def expectDicomComplete(): ElementProbe = probe
      .request(1)
      .expectComplete()
  }

}
