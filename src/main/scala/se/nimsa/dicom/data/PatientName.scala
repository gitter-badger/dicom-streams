package se.nimsa.dicom.data

import se.nimsa.dicom.data.DicomParsing.parsePN

case class ComponentGroup(alphabetic: String, ideographic: String, phonetic: String) {
  override def toString: String = s"$alphabetic=$ideographic=$phonetic".replaceAll("=+$", "")
}

case class PatientName(familyName: ComponentGroup, givenName: ComponentGroup, middleName: ComponentGroup, prefix: ComponentGroup, suffix: ComponentGroup) {
  override def toString: String = s"$familyName^$givenName^$middleName^$prefix^$suffix".replaceAll("\\^+$", "")
}

object PatientName {
  def parse(s: String): Seq[PatientName] = parsePN(s)
}