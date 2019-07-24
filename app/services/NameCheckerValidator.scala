package services

import java.util.UUID

import model.{Category, ResponseRule, RuleMatch}
import utils.Validator

import scala.concurrent.{ExecutionContext, Future}
import model.WikiSuggestion
import model.WikiAbstract

class NameCheckerValidator(
    nameFinder: NameFinder,
    wikiNameSearcher: WikiNameSearcher
)(implicit ec: ExecutionContext)
    extends Validator {
  def getCategory = "Name"
  def getRules = List.empty
  def check(request: ValidatorRequest): Future[List[RuleMatch]] = {
    val names = nameFinder.findNames(request.text)
    val results = names.map { name =>
      wikiNameSearcher.fetchWikiMatchesForName(name.text).flatMap {
        case Some(result) => {
          val firstSearchResult = result.hits.hits.headOption.map { hit =>
            hit._source.title
          }
          Future.successful(RuleMatch(
            getResponseRule,
            name.from,
            name.to,
            message = firstSearchResult.getOrElse(List("No result")).mkString("")
          ))
        }
        case None =>
          Future.failed(
            new Throwable(s"Could not parse result for name ${name.text}")
          )
      }
    }
    Future.sequence(results)
  }
  private def getResponseRule =
    ResponseRule(
      UUID.randomUUID().toString,
      "Name check description",
      Category("name-check", "Name check", "teal"),
      ""
    )
}