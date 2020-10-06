package matchers

import java.io.File

import model.{LTRule, LTRuleXML, RuleMatch}
import org.languagetool._
import org.languagetool.rules.spelling.morfologik.suggestions_ordering.SuggestionsOrdererConfig
import org.languagetool.rules.{CategoryId, Rule => LanguageToolRule}
import play.api.Logging
import services.MatcherRequest
import utils.{Matcher, MatcherCompanion}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import model.Category
import org.languagetool.rules.patterns.PatternRuleLoader
import org.languagetool.rules.patterns.PatternRule
import org.languagetool.rules.patterns.AbstractPatternRule
import scala.xml.XML
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.ByteArrayInputStream

class LanguageToolFactory(
                           maybeLanguageModelDir: Option[File],
                           useLanguageModelRules: Boolean = false) extends Logging {

  def createInstance(ruleXMLs: List[LTRuleXML], defaultRuleIds: List[String] = Nil)(implicit ec: ExecutionContext): Either[List[Throwable], Matcher] = {
    val language: Language = Languages.getLanguageForShortCode("en")
    val cache: ResultCache = new ResultCache(10000)
    val userConfig: UserConfig = new UserConfig()

    val instance = new JLanguageTool(language, cache, userConfig)

    maybeLanguageModelDir.foreach { languageModel =>
      SuggestionsOrdererConfig.setNgramsPath(languageModel.toString)
      if (useLanguageModelRules) instance.activateLanguageModelRules(languageModel)
    }

    // Disable all default rules, apart from those we'd explicitly like
    instance.getAllRules().asScala.foreach { rule =>
      if (!defaultRuleIds.contains(rule.getId())) {
        instance.disableRule(rule.getId())
      }
    }

    // Add custom rules
    applyXMLRules(instance, ruleXMLs) map { _ =>
      val matcher = new LanguageToolMatcher(instance)
      logger.info(s"Added ${ruleXMLs.size} rules and enabled ${defaultRuleIds.size} default rules for matcher instance with id: ${matcher.getId()}")
      matcher
    }
  }

  /**
    * As a side-effect, apply the given ltRuleXmls to the given
    * LanguageTool instance and enable them for matching.
    */
  private def applyXMLRules(instance: JLanguageTool, ltRuleXmls: List[LTRuleXML]): Either[List[Throwable], Unit] = {
    val maybeRuleErrors = getLTRulesFromXML(ltRuleXmls) match {
      case Success(rules) => rules.map { rule => Try {
          instance.addRule(rule)
          instance.enableRule(rule.getId())
        }
      }
      case Failure(e) => List(Failure(e))
    }

    maybeRuleErrors.flatMap {
      case Success(_) => None
      case Failure(e) => {
        logger.error(e.getMessage(), e)
        Some(e)
      }
    } match {
      case Nil => Right(())
      case errors => Left(errors)
    }
  }

  private def getLTRulesFromXML(rules: List[LTRuleXML]): Try[List[AbstractPatternRule]] = rules match {
    case Nil => Success(Nil)
    case r => {
      val loader = new PatternRuleLoader()
      getXMLStreamFromLTRules(rules) flatMap {
        xmlStream => {
          Try(loader.getRules(xmlStream, "languagetool-generated-xml").asScala.toList)
        }
      }
    }
  }

  private def getXMLStreamFromLTRules(rules: List[LTRuleXML]): Try[ByteArrayInputStream] = Try {
    val rulesByCategory = rules.groupBy(_.category)
    val rulesXml = rulesByCategory.map {
      case (category, rules) =>
        <category id={category.id} name={category.name} type="grammar">
          {rules.map { rule =>
            <rule id={rule.id} name={rule.description}>
              {XML.loadString(s"<temp>${rule.xml}</temp>").child}
            </rule>
          }}
        </category>
    }

    // Temporarily hardcode language settings
    val ruleXml = <rules lang="en">{rulesXml}</rules>

    val outputStream = new ByteArrayOutputStream()
    val writer = new OutputStreamWriter(outputStream)
    XML.write(writer, ruleXml, "UTF-8", xmlDecl = true, doctype = xml.dtd.DocType("rules"))
    writer.close()

    new ByteArrayInputStream(outputStream.toByteArray())
  }
}

object LanguageToolMatcher extends MatcherCompanion {
  def getType = "languageTool"
}

/**
  * A Matcher that wraps a LanguageTool instance.
  */
class LanguageToolMatcher(instance: JLanguageTool) extends Matcher {

  def getType = LanguageToolMatcher.getType

  def getCategories = instance
    .getAllActiveRules()
    .asScala
    .toList
    .map { rule => Category.fromLT(rule.getCategory()) }
    .toSet

  def check(request: MatcherRequest)(implicit ec: ExecutionContext): Future[List[RuleMatch]] = Future {
    request.blocks.flatMap { block =>
      instance.check(block.text).asScala.map(RuleMatch.fromLT(_, block, getType)).toList.map { ruleMatch =>
        ruleMatch.copy(
          fromPos = ruleMatch.fromPos + block.from,
          toPos = ruleMatch.toPos + block.from
        )
      }
    }
  }

  def getRules: List[LTRule] = {
    instance.getAllActiveRules.asScala.toList.flatMap {
      case rule: LanguageToolRule => Some(LTRule.fromLT(rule))
      case _ => None
    }
  }
}
