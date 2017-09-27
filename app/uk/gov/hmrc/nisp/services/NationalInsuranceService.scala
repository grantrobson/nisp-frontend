/*
 * Copyright 2017 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.nisp.services

import uk.gov.hmrc.domain.Nino
import uk.gov.hmrc.nisp.connectors.{NationalInsuranceConnector, NispConnector}
import uk.gov.hmrc.nisp.models.enums.Exclusion
import uk.gov.hmrc.nisp.models.enums.Exclusion.Exclusion
import uk.gov.hmrc.nisp.models.{NIRecordTaxYear, NIResponse, NationalInsuranceRecord, NationalInsuranceTaxYear}
import uk.gov.hmrc.nisp.utils.Constants
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext._
import uk.gov.hmrc.time.TaxYear

import scala.concurrent.Future
import uk.gov.hmrc.http.{ HeaderCarrier, Upstream4xxResponse }

trait NationalInsuranceService {
  def getSummary(nino: Nino)(implicit hc: HeaderCarrier): Future[Either[Exclusion, NationalInsuranceRecord]]
}


trait NationalInsuranceConnection {

  final val ExclusionCodeDead = "EXCLUSION_DEAD"
  final val ExclusionCodeManualCorrespondence = "EXCLUSION_MANUAL_CORRESPONDENCE"
  final val ExclusionCodeIsleOfMan = "EXCLUSION_ISLE_OF_MAN"
  final val ExclusionCodeMarriedWomen = "EXCLUSION_MARRIED_WOMENS_REDUCED_RATE"

  final val ExclusionErrorCode = 403

  val nationalInsuranceConnector: NationalInsuranceConnector

  def getSummary(nino: Nino)(implicit hc: HeaderCarrier): Future[Either[Exclusion, NationalInsuranceRecord]] = {
    nationalInsuranceConnector.getNationalInsurance(nino)
      .map(ni => Right(ni.copy(taxYears = ni.taxYears.sortBy(_.taxYear)(Ordering[String].reverse))))
      .recover {
        case Upstream4xxResponse(message, ExclusionErrorCode, _, _) if message.contains(ExclusionCodeDead) =>
          Left(Exclusion.Dead)
        case Upstream4xxResponse(message, ExclusionErrorCode, _, _) if message.contains(ExclusionCodeManualCorrespondence) =>
          Left(Exclusion.ManualCorrespondenceIndicator)
        case Upstream4xxResponse(message, ExclusionErrorCode, _, _) if message.contains(ExclusionCodeIsleOfMan) =>
          Left(Exclusion.IsleOfMan)
        case Upstream4xxResponse(message, ExclusionErrorCode, _, _) if message.contains(ExclusionCodeMarriedWomen) =>
          Left(Exclusion.MarriedWomenReducedRateElection)
      }
  }
}

trait NispConnectionNI {
  val nispConnector: NispConnector

  private def filterExclusions(exclusions: List[Exclusion]): Exclusion = {
    if (exclusions.contains(Exclusion.Dead)) {
      Exclusion.Dead
    } else if (exclusions.contains(Exclusion.ManualCorrespondenceIndicator)) {
      Exclusion.ManualCorrespondenceIndicator
    } else if (exclusions.contains(Exclusion.IsleOfMan)) {
      Exclusion.IsleOfMan
    } else if (exclusions.contains(Exclusion.MarriedWomenReducedRateElection)) {
      Exclusion.MarriedWomenReducedRateElection
    } else {
      throw new RuntimeException(s"Un-accounted for exclusion in NispConnectionNI: $exclusions")
    }
  }

  private[services] def startYearToTaxYear(startYear: Int): String = {
    val endYear = TaxYear(startYear).finishYear
    s"$startYear-${endYear.toString.substring(Constants.shortYearStartCharacter, Constants.shortYearEndCharacter)}"
  }

  private def transformTaxYear(niRecordTaxYear: NIRecordTaxYear): NationalInsuranceTaxYear = {
    NationalInsuranceTaxYear(
      taxYear = startYearToTaxYear(niRecordTaxYear.taxYear),
      qualifying = niRecordTaxYear.qualifying,
      classOneContributions = niRecordTaxYear.classOneContributions,
      classTwoCredits = niRecordTaxYear.classTwoCredits,
      classThreeCredits = niRecordTaxYear.classThreeCredits,
      otherCredits = niRecordTaxYear.otherCredits,
      classThreePayable = niRecordTaxYear.classThreePayable.getOrElse(0),
      classThreePayableBy = niRecordTaxYear.classThreePayableBy.map(_.localDate),
      classThreePayableByPenalty = niRecordTaxYear.classThreePayableByPenalty.map(_.localDate),
      payable = niRecordTaxYear.payable,
      underInvestigation = niRecordTaxYear.underInvestigation
    )
  }

  def getSummary(nino: Nino)(implicit hc: HeaderCarrier): Future[Either[Exclusion, NationalInsuranceRecord]] = {
    nispConnector.connectToGetNIResponse(nino) map {
      case NIResponse(_, _, Some(exclusionsModel)) => {
        Left(filterExclusions(exclusionsModel.exclusions))
      }
      case NIResponse(Some(record), Some(summary), None) => {
        Right(NationalInsuranceRecord(
          qualifyingYears = summary.noOfQualifyingYears,
          qualifyingYearsPriorTo1975 = summary.pre75QualifyingYears.getOrElse(0),
          numberOfGaps = summary.noOfNonQualifyingYears,
          numberOfGapsPayable = summary.numberOfPayableGaps,
          dateOfEntry = Some(summary.dateOfEntry.localDate),
          homeResponsibilitiesProtection = summary.homeResponsibilitiesProtection,
          earningsIncludedUpTo = summary.earningsIncludedUpTo.localDate,
          taxYears = record.taxYears.sortBy(_.taxYear)(Ordering[Int].reverse) map transformTaxYear
        ))
      }
      case _ => throw new RuntimeException("NI Response Model is unmatchable. This is probably a logic error.")
    }
  }
}

object NationalInsuranceService extends NationalInsuranceService with NationalInsuranceConnection {
  override val nationalInsuranceConnector: NationalInsuranceConnector = NationalInsuranceConnector
}

object NispNationalInsuranceService extends NationalInsuranceService with NispConnectionNI {
  override val nispConnector: NispConnector = NispConnector
}
