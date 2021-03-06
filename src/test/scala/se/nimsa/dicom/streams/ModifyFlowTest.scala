package se.nimsa.dicom.streams

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import akka.util.ByteString
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import se.nimsa.dicom.data._
import se.nimsa.dicom.data.{Tag, TagPath, VR}

import scala.concurrent.ExecutionContextExecutor

class ModifyFlowTest extends TestKit(ActorSystem("ModifyFlowSpec")) with FlatSpecLike with Matchers with BeforeAndAfterAll {

  import ModifyFlow._
  import TestUtils._
  import se.nimsa.dicom.data.DicomParts._
  import se.nimsa.dicom.data.TestData._

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  override def afterAll(): Unit = system.terminate()

  "The modify flow" should "modify the value of the specified elements" in {
    val bytes = studyDate() ++ patientNameJohnDoe()

    val mikeBytes = ByteString('M', 'i', 'k', 'e')

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(
        TagModification.contains(TagPath.fromTag(Tag.StudyDate), _ => ByteString.empty, insert = false),
        TagModification.contains(TagPath.fromTag(Tag.PatientName), _ => mikeBytes, insert = false)))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.StudyDate, VR.DA, 0)
      .expectHeader(Tag.PatientName, VR.PN, mikeBytes.length)
      .expectValueChunk(mikeBytes)
      .expectDicomComplete()
  }

  it should "not modify elements in datasets other than the dataset the tag path points to" in {
    val bytes = sequence(Tag.DerivationCodeSequence) ++ item() ++ patientNameJohnDoe() ++ studyDate() ++ itemDelimitation() ++ sequenceDelimitation()

    val mikeBytes = ByteString('M', 'i', 'k', 'e')

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(TagModification.contains(TagPath.fromTag(Tag.PatientName), _ => mikeBytes, insert = false)))

    source.runWith(TestSink.probe[DicomPart])
      .expectSequence(Tag.DerivationCodeSequence)
      .expectItem(1)
      .expectHeader(Tag.PatientName, VR.PN, patientNameJohnDoe().length - 8)
      .expectValueChunk(patientNameJohnDoe().drop(8))
      .expectHeader(Tag.StudyDate)
      .expectValueChunk()
      .expectItemDelimitation()
      .expectSequenceDelimitation()
      .expectDicomComplete()
  }

  it should "insert elements if not present" in {
    val bytes = patientNameJohnDoe()

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(TagModification.contains(TagPath.fromTag(Tag.StudyDate), _ => studyDate().drop(8), insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.StudyDate, VR.DA, studyDate().length - 8)
      .expectValueChunk(studyDate().drop(8))
      .expectHeader(Tag.PatientName, VR.PN, patientNameJohnDoe().length - 8)
      .expectValueChunk(patientNameJohnDoe().drop(8))
      .expectDicomComplete()
  }

  it should "insert elements if not present also at end of dataset" in {
    val bytes = studyDate()

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(TagModification.contains(TagPath.fromTag(Tag.PatientName), _ => patientNameJohnDoe().drop(8), insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.StudyDate, VR.DA, studyDate().length - 8)
      .expectValueChunk(studyDate().drop(8))
      .expectHeader(Tag.PatientName, VR.PN, patientNameJohnDoe().length - 8)
      .expectValueChunk(patientNameJohnDoe().drop(8))
      .expectDicomComplete()
  }

  it should "insert elements if not present also at end of dataset when last element is empty" in {
    val bytes = tagToBytesLE(0x00080050) ++ ByteString("SH") ++ shortToBytesLE(0x0000)

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(TagModification.contains(TagPath.fromTag(Tag.SOPInstanceUID), _ => ByteString("1.2.3.4 "), insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.SOPInstanceUID, VR.UI, 8)
      .expectValueChunk(8)
      .expectHeader(Tag.AccessionNumber, VR.SH, 0)
      .expectDicomComplete()
  }

  it should "insert elements between a normal attribute and a sequence" in {
    val bytes = studyDate() ++ sequence(Tag.AbstractPriorCodeSequence) ++ sequenceDelimitation()

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(TagModification.contains(TagPath.fromTag(Tag.PatientName), _ => patientNameJohnDoe().drop(8), insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.StudyDate, VR.DA, studyDate().length - 8)
      .expectValueChunk(studyDate().drop(8))
      .expectHeader(Tag.PatientName, VR.PN, patientNameJohnDoe().length - 8)
      .expectValueChunk(patientNameJohnDoe().drop(8))
      .expectSequence(Tag.AbstractPriorCodeSequence)
      .expectSequenceDelimitation()
      .expectDicomComplete()
  }

  it should "insert elements between a sequence and a normal attribute" in {
    val bytes = sequence(Tag.DerivationCodeSequence) ++ sequenceDelimitation() ++ patientID()

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(TagModification.contains(TagPath.fromTag(Tag.PatientName), _ => patientNameJohnDoe().drop(8), insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectSequence(Tag.DerivationCodeSequence)
      .expectSequenceDelimitation()
      .expectHeader(Tag.PatientName, VR.PN, patientNameJohnDoe().length - 8)
      .expectValueChunk(patientNameJohnDoe().drop(8))
      .expectHeader(Tag.PatientID, VR.LO, patientID().length - 8)
      .expectValueChunk(patientID().drop(8))
      .expectDicomComplete()
  }

  it should "insert elements between two sequences" in {
    val bytes = sequence(Tag.DerivationCodeSequence) ++ sequenceDelimitation() ++ sequence(Tag.AbstractPriorCodeSequence) ++ sequenceDelimitation()

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(TagModification.contains(TagPath.fromTag(Tag.PatientName), _ => patientNameJohnDoe().drop(8), insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectSequence(Tag.DerivationCodeSequence)
      .expectSequenceDelimitation()
      .expectHeader(Tag.PatientName, VR.PN, patientNameJohnDoe().length - 8)
      .expectValueChunk(patientNameJohnDoe().drop(8))
      .expectSequence(Tag.AbstractPriorCodeSequence)
      .expectSequenceDelimitation()
      .expectDicomComplete()
  }

  it should "modify, not insert, when 'insert' elements are already present" in {
    val bytes = studyDate() ++ patientNameJohnDoe()

    val mikeBytes = ByteString('M', 'i', 'k', 'e')

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(
        TagModification.contains(TagPath.fromTag(Tag.StudyDate), _ => ByteString.empty, insert = true),
        TagModification.contains(TagPath.fromTag(Tag.PatientName), _ => mikeBytes, insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.StudyDate, VR.DA, 0)
      .expectHeader(Tag.PatientName, VR.PN, mikeBytes.length)
      .expectValueChunk(mikeBytes)
      .expectDicomComplete()
  }

  it should "insert all relevant elements below the current tag number" in {
    val bytes = patientNameJohnDoe()

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(
        TagModification.contains(TagPath.fromTag(Tag.SeriesDate), _ => studyDate().drop(8), insert = true),
        TagModification.contains(TagPath.fromTag(Tag.StudyDate), _ => studyDate().drop(8), insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.StudyDate, VR.DA, studyDate().length - 8)
      .expectValueChunk(studyDate().drop(8))
      .expectHeader(Tag.SeriesDate, VR.DA, studyDate().length - 8)
      .expectValueChunk(studyDate().drop(8))
      .expectHeader(Tag.PatientName, VR.PN, patientNameJohnDoe().length - 8)
      .expectValueChunk(patientNameJohnDoe().drop(8))
      .expectDicomComplete()
  }

  it should "not insert elements if dataset contains no elements" in {
    val source = Source.empty
      .via(new ParseFlow())
      .via(modifyFlow(TagModification.contains(TagPath.fromTag(Tag.SeriesDate), _ => studyDate().drop(8), insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectDicomComplete()
  }

  it should "insert elements in sequences if sequence is present but element is not present" in {
    val bytes = sequence(Tag.DerivationCodeSequence) ++ item() ++ patientNameJohnDoe() ++ itemDelimitation() ++ sequenceDelimitation()

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(
        TagModification.contains(TagPath.fromSequence(Tag.DerivationCodeSequence, 1).thenTag(Tag.StudyDate), _ => studyDate().drop(8), insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectSequence(Tag.DerivationCodeSequence)
      .expectItem(1)
      .expectHeader(Tag.StudyDate)
      .expectValueChunk()
      .expectHeader(Tag.PatientName)
      .expectValueChunk()
      .expectItemDelimitation()
      .expectSequenceDelimitation()
      .expectDicomComplete()
  }

  it should "skip inserting elements in missing sequences" in {
    val bytes = patientNameJohnDoe()

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(
        TagModification.contains(TagPath.fromSequence(Tag.DerivationCodeSequence).thenTag(Tag.StudyDate), _ => studyDate().drop(8), insert = true),
        TagModification.contains(TagPath.fromSequence(Tag.DerivationCodeSequence).thenTag(Tag.PatientName), _ => ByteString.empty, insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.PatientName)
      .expectValueChunk()
      .expectDicomComplete()
  }

  it should "not insert unknown elements" in {
    val bytes = patientNameJohnDoe()

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(
        TagModification.contains(TagPath.fromTag(0x00200021), _ => ByteString(1, 2, 3, 4), insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.PatientName)
      .expectValueChunk()
      .expectDicomError()
  }

  it should "not insert sequences" in {
    val bytes = patientNameJohnDoe()

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(
        TagModification.contains(TagPath.fromTag(Tag.DerivationCodeSequence), _ => ByteString.empty, insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectDicomError()
  }

  it should "insert into the correct sequence item" in {
    val bytes = sequence(Tag.DerivationCodeSequence) ++ item() ++ patientNameJohnDoe() ++ itemDelimitation() ++ item() ++ patientNameJohnDoe() ++ itemDelimitation() ++ sequenceDelimitation()

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(
        TagModification.contains(TagPath.fromSequence(Tag.DerivationCodeSequence, 2).thenTag(Tag.StudyDate), _ => studyDate().drop(8), insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectSequence(Tag.DerivationCodeSequence)
      .expectItem(1)
      .expectHeader(Tag.PatientName)
      .expectValueChunk()
      .expectItemDelimitation()
      .expectItem(2)
      .expectHeader(Tag.StudyDate)
      .expectValueChunk()
      .expectHeader(Tag.PatientName)
      .expectValueChunk()
      .expectItemDelimitation()
      .expectSequenceDelimitation()
      .expectDicomComplete()
  }

  it should "modify the correct sequence item" in {
    val bytes = sequence(Tag.DerivationCodeSequence) ++ item() ++ patientNameJohnDoe() ++ itemDelimitation() ++ item() ++ patientNameJohnDoe() ++ itemDelimitation() ++ sequenceDelimitation()

    val mikeBytes = ByteString('M', 'i', 'k', 'e')

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(
        TagModification.contains(TagPath.fromSequence(Tag.DerivationCodeSequence, 2).thenTag(Tag.PatientName), _ => mikeBytes, insert = false)))

    source.runWith(TestSink.probe[DicomPart])
      .expectSequence(Tag.DerivationCodeSequence)
      .expectItem(1)
      .expectHeader(Tag.PatientName, VR.PN, patientNameJohnDoe().drop(8).length)
      .expectValueChunk()
      .expectItemDelimitation()
      .expectItem(2)
      .expectHeader(Tag.PatientName, VR.PN, mikeBytes.length)
      .expectValueChunk()
      .expectItemDelimitation()
      .expectSequenceDelimitation()
      .expectDicomComplete()
  }

  it should "insert into all sequence items" in {
    val bytes = sequence(Tag.DerivationCodeSequence) ++ item() ++ patientNameJohnDoe() ++ itemDelimitation() ++ item() ++ patientNameJohnDoe() ++ itemDelimitation() ++ sequenceDelimitation()

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(
        TagModification.contains(TagPath.fromSequence(Tag.DerivationCodeSequence).thenTag(Tag.StudyDate), _ => studyDate().drop(8), insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectSequence(Tag.DerivationCodeSequence)
      .expectItem(1)
      .expectHeader(Tag.StudyDate)
      .expectValueChunk()
      .expectHeader(Tag.PatientName)
      .expectValueChunk()
      .expectItemDelimitation()
      .expectItem(2)
      .expectHeader(Tag.StudyDate)
      .expectValueChunk()
      .expectHeader(Tag.PatientName)
      .expectValueChunk()
      .expectItemDelimitation()
      .expectSequenceDelimitation()
      .expectDicomComplete()
  }

  it should "modify all sequence items" in {
    val bytes = sequence(Tag.DerivationCodeSequence) ++ item() ++ patientNameJohnDoe() ++ itemDelimitation() ++ item() ++ patientNameJohnDoe() ++ itemDelimitation() ++ sequenceDelimitation()

    val mikeBytes = ByteString('M', 'i', 'k', 'e')

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(
        TagModification.contains(TagPath.fromSequence(Tag.DerivationCodeSequence).thenTag(Tag.PatientName), _ => mikeBytes, insert = false)))

    source.runWith(TestSink.probe[DicomPart])
      .expectSequence(Tag.DerivationCodeSequence)
      .expectItem(1)
      .expectHeader(Tag.PatientName, VR.PN, mikeBytes.length)
      .expectValueChunk()
      .expectItemDelimitation()
      .expectItem(2)
      .expectHeader(Tag.PatientName, VR.PN, mikeBytes.length)
      .expectValueChunk()
      .expectItemDelimitation()
      .expectSequenceDelimitation()
      .expectDicomComplete()
  }

  it should "correctly sort elements with tag numbers exceeding the positive range of its signed integer representation" in {
    val bytes = preamble ++ fmiGroupLength(transferSyntaxUID()) ++ transferSyntaxUID() ++ ByteString(0xFF, 0xFF, 0xFF, 0xFF, 68, 65, 10, 0, 49, 56, 51, 49, 51, 56, 46, 55, 54, 53)

    val mikeBytes = ByteString('M', 'i', 'k', 'e')

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(
        TagModification.contains(TagPath.fromTag(Tag.PatientName), _ => mikeBytes, insert = true)))

    source.runWith(TestSink.probe[DicomPart])
      .expectPreamble()
      .expectHeader(Tag.FileMetaInformationGroupLength)
      .expectValueChunk()
      .expectHeader(Tag.TransferSyntaxUID)
      .expectValueChunk()
      .expectHeader(Tag.PatientName, VR.PN, mikeBytes.length)
      .expectValueChunk()
      .expectHeader(-1, VR.DA, 10)
      .expectValueChunk()
      .expectDicomComplete()
  }

  it should "work also with the endsWith modification matcher" in {
    val bytes = studyDate() ++ sequence(Tag.DerivationCodeSequence) ++ item() ++ studyDate() ++ patientNameJohnDoe() ++ itemDelimitation() ++ sequenceDelimitation()

    val studyBytes = ByteString("2012-01-01")

    val source = Source.single(bytes)
      .via(new ParseFlow())
      .via(modifyFlow(TagModification.endsWith(TagPath.fromTag(Tag.StudyDate), _ => studyBytes, insert = false)))

    source.runWith(TestSink.probe[DicomPart])
      .expectHeader(Tag.StudyDate)
      .expectValueChunk(studyBytes)
      .expectSequence(Tag.DerivationCodeSequence)
      .expectItem(1)
      .expectHeader(Tag.StudyDate)
      .expectValueChunk(studyBytes)
      .expectHeader(Tag.PatientName)
      .expectValueChunk()
      .expectItemDelimitation()
      .expectSequenceDelimitation()
      .expectDicomComplete()
  }

}
