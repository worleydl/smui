package controllers

import java.time.LocalDateTime

import util.control.Breaks._
import scala.collection.mutable.ListBuffer
import models._
import models.rules._

object ApiControllerHelperRulesTxt {

  trait PreliminaryRule {
    def term: String
  }
  case class PreliminarySynonymRule(term: String, var synonymType: Int = SynonymRule.TYPE_DIRECTED) extends PreliminaryRule
  case class PreliminaryUpDownRule(term: String, upDownType: Int, boostMalusValue: Int) extends PreliminaryRule
  case class PreliminaryFilterRule(term: String) extends PreliminaryRule
  case class PreliminaryDeleteRule(term: String) extends PreliminaryRule
  case class PreliminarySearchInput(term: String, var rules: List[PreliminaryRule], var allSynonymTermHash: String = null, var remainingRulesHash: String = null)

  def importFromFilePayload(filePayload: String, solrIndexId: SolrIndexId, searchManagementRepository: SearchManagementRepository): (Int, Int, Int, Int, Int) = {
    println("In importFromFilePayload :: filePayload = >>>" + filePayload)
    println(":: solrIndexId = " + solrIndexId)

    // PARSE
    // engineer rules data structure from rules.txt file payload
    val rulesTxtModel = ListBuffer.empty[PreliminarySearchInput]
    var currInput: PreliminarySearchInput = null
    var currRules: ListBuffer[PreliminaryRule] = null
    var retstatCountRulesTxtLinesSkipped = 0
    var retstatCountRulesTxtUnkownConvert = 0
    for(ruleLine: String <- filePayload.split("\n")) {
      // if line is empty or contains a comment, skip
      val b_lineEmptyOrComment = (ruleLine.trim().length() < 1) || (ruleLine.trim().startsWith("#"))
      if(!b_lineEmptyOrComment) {
        // match pattern for search input
        // TODO make robust against input is not first syntax element in line orders
        "(.*?) =>".r.findFirstMatchIn(ruleLine.trim()) match {
          case Some(m) => {
            if(currRules == null) {
              currRules = ListBuffer.empty[PreliminaryRule]
            } else {
              // commit collected rules to curr_input & empty
              currInput.rules = currRules.clone().toList
              currRules = ListBuffer.empty[PreliminaryRule]
            }
            currInput = new PreliminarySearchInput(m.group(1), List.empty[PreliminaryRule])
            rulesTxtModel += currInput
          }
          case None => {
            // match pattern for synonyms (directed-only assumed)
            "^[\\s]*?SYNONYM: (.*)".r.findFirstMatchIn(ruleLine.trim()) match {
              case Some(m) => {
                currRules += PreliminarySynonymRule(m.group(1))
              }
              case None => {
                // match pattern for UP/DOWN
                "^[\\s]*?(UP|DOWN)\\((\\d*)\\): (.*)".r.findFirstMatchIn(ruleLine.trim()) match {
                  case Some(m) => {
                    // TODO make robust against, neither "UP" nor "DOWN" appeared
                    var upDownType = UpDownRule.TYPE_UP
                    if(m.group(1).trim() == "DOWN") {
                      upDownType = UpDownRule.TYPE_DOWN
                    }
                    // TODO make robust against string not convertable to integer
                    val boostMalusValue = m.group(2).trim().toInt
                    currRules += PreliminaryUpDownRule(m.group(3), upDownType, boostMalusValue)
                  }
                  case None => {
                    // match pattern for FILTER
                    "^[\\s]*?FILTER: (.*)".r.findFirstMatchIn(ruleLine.trim()) match {
                      case Some(m) => {
                        currRules += PreliminaryFilterRule(m.group(1))
                      }
                      case None => {
                        // match pattern for DELETE
                        "^[\\s]*?DELETE: (.*)".r.findFirstMatchIn(ruleLine.trim()) match {
                          case Some(m) => {
                            currRules += PreliminaryDeleteRule(m.group(1))
                          }
                          case None => {
                            // unknown, if reached that point
                            println("Cannot convert line >>>" + ruleLine)
                            retstatCountRulesTxtUnkownConvert += 1
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      } else {
        retstatCountRulesTxtLinesSkipped += 1
      }
    }
    // commit last rules
    currInput.rules = currRules.clone().toList
    // print some stats
    println("retstatCountRulesTxtLinesSkipped = " + retstatCountRulesTxtLinesSkipped)
    println("retstatCountRulesTxtUnkownConvert = " + retstatCountRulesTxtUnkownConvert)

    // POST PROCESS
    // annotate rulesTxtModel with fingerprints related to synonym/other rules
    def synonymFingerprint(input: PreliminarySearchInput): String = {
      val termsOfInput = input.term ::
        input.rules.filter(_.isInstanceOf[PreliminarySynonymRule])
        .map(_.term)
      val sortedTermsOfInput = termsOfInput.sorted
      return sortedTermsOfInput.mkString("")
    }
    def otherRulesFingerprint(input: PreliminarySearchInput): String = {
      val remainingRules = input.rules.filter(!_.isInstanceOf[PreliminarySynonymRule])
        .sortBy(_.term)
      if(remainingRules.size < 1) {
        return "0"
      } else {
        return remainingRules.hashCode().toString
      }
    }
    for(input <- rulesTxtModel) {
      input.allSynonymTermHash = synonymFingerprint(input)
      input.remainingRulesHash = otherRulesFingerprint(input)
    }
    // start collecting retstat (= return statistics)
    val retstatCountRulesTxtInputs = rulesTxtModel.size
    println("retstatCountRulesTxtInputs = " + retstatCountRulesTxtInputs)
    // consolidate to target SMUI model data structure (whilst identifying undirected synonyms)
    val importPrelimSearchInputs = ListBuffer.empty[PreliminarySearchInput]
    val skip_i = ListBuffer.empty[Int] // indices of inputs & rules skipped, because they are collapsed as an undirected synonym
    for((a_input, i) <- rulesTxtModel.zipWithIndex) {
      if(!skip_i.toList.contains(i)) {
        for((b_input, j) <- rulesTxtModel.zipWithIndex) {
          if(i != j) {
            if((a_input.allSynonymTermHash == b_input.allSynonymTermHash) && (a_input.remainingRulesHash == b_input.remainingRulesHash)) {
              println("Found matching undirected synonym on i = " + i + ", j = " + j)
              // find matching synonym rule in a_input
              for(a_synonymRule <- a_input.rules.filter(_.isInstanceOf[PreliminarySynonymRule])) breakable {
                if(a_synonymRule.term == b_input.term) {
                  println("^-- Found according synonym for" + b_input.term + " in = " + a_synonymRule)
                  a_synonymRule.asInstanceOf[PreliminarySynonymRule].synonymType = SynonymRule.TYPE_UNDIRECTED
                  break
                }
              }
              skip_i += j
            }
          }
        }
        importPrelimSearchInputs += a_input
      }
    }
    val retstatCountConsolidatedInputs = importPrelimSearchInputs.size
    println("retstatCountConsolidatedInputs = " + retstatCountConsolidatedInputs)
    val retstatCountConsolidatedRules = importPrelimSearchInputs
      .toList
      .foldLeft(0) {
        (s, i) => s + i.rules.size
      }
    println("retstatCountConsolidatedRules = " + retstatCountConsolidatedRules)

    // IMPORT INTO DB
    // convert
    def preliminaryToFinalInput(preliminarySearchInput: PreliminarySearchInput): SearchInputWithRules = {
      val now = LocalDateTime.now()
      // define converter
      def preliminaryToFinalSynonymRule(preliminarySynonymRule: PreliminarySynonymRule): SynonymRule = {
        return new SynonymRule(
          SynonymRuleId(),
          preliminarySynonymRule.synonymType,
          preliminarySynonymRule.term,
          true
        )
      }
      def preliminaryToFinalUpDownRule(preliminaryUpDownRule: PreliminaryUpDownRule): UpDownRule = {
        return new UpDownRule(
          UpDownRuleId(),
          preliminaryUpDownRule.upDownType,
          preliminaryUpDownRule.boostMalusValue,
          preliminaryUpDownRule.term,
          true
        )
      }
      def preliminaryToFinalFilterRule(preliminaryFilterRule: PreliminaryFilterRule): FilterRule = {
        return new FilterRule(
          FilterRuleId(),
          preliminaryFilterRule.term,
          true
        )
      }
      def preliminaryToFinalDeleteRule(preliminaryDeleteRule: PreliminaryDeleteRule): DeleteRule = {
        return new DeleteRule(
          DeleteRuleId(),
          preliminaryDeleteRule.term,
          true
        )
      }
      // convert rules
      val synonymRules = preliminarySearchInput
        .rules
        .filter(_.isInstanceOf[PreliminarySynonymRule])
        .map(r => preliminaryToFinalSynonymRule(r.asInstanceOf[PreliminarySynonymRule]))
      val upDownRules = preliminarySearchInput
        .rules
        .filter(_.isInstanceOf[PreliminaryUpDownRule])
        .map(r => preliminaryToFinalUpDownRule(r.asInstanceOf[PreliminaryUpDownRule]))
      val filterRules = preliminarySearchInput
        .rules
        .filter(_.isInstanceOf[PreliminaryFilterRule])
        .map(r => preliminaryToFinalFilterRule(r.asInstanceOf[PreliminaryFilterRule]))
      val deleteRules = preliminarySearchInput
        .rules
        .filter(_.isInstanceOf[PreliminaryDeleteRule])
        .map(r => preliminaryToFinalDeleteRule(r.asInstanceOf[PreliminaryDeleteRule]))
      // convert final input (passing its rules)
      return new SearchInputWithRules(
        SearchInputId("--empty--"),
        preliminarySearchInput.term,
        synonymRules,
        upDownRules,
        filterRules,
        deleteRules,
        Nil,
        Seq.empty[InputTag]
      )
    }
    val finalInputs = importPrelimSearchInputs.map(preliminaryToFinalInput(_))
    println("finalInputs.size = " + finalInputs.size)
    def __DEBUG_count_all_rules(i: SearchInputWithRules): Int = {
      return i.synonymRules.size +
        i.upDownRules.size +
        i.filterRules.size +
        i.deleteRules.size
    }
    println("finalInputs :: total rules = " + finalInputs
      .toList
      .foldLeft(0) {
        (s, i) => s + __DEBUG_count_all_rules(i)
      }
    )
    // write to DB
    finalInputs.map { searchInput =>
      def inputWithDbId(input: SearchInputWithRules, dbId: SearchInputId): SearchInputWithRules = {
        return new SearchInputWithRules(
          dbId,
          input.term,
          input.synonymRules,
          input.upDownRules,
          input.filterRules,
          input.deleteRules,
          input.redirectRules,
          input.tags
        )
      }
      // first create entity
      val searchInputId = searchManagementRepository.addNewSearchInput(
        solrIndexId, searchInput.term, Seq.empty
      )
      // then update (incl rules)
      searchManagementRepository.updateSearchInput(
        inputWithDbId(searchInput, searchInputId)
      )
    }
    // return stats
    val retStatistics = (
      retstatCountRulesTxtInputs,
      retstatCountRulesTxtLinesSkipped,
      retstatCountRulesTxtUnkownConvert,
      retstatCountConsolidatedInputs,
      retstatCountConsolidatedRules
    )
    return retStatistics
  }

}