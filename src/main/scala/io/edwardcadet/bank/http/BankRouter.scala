package io.edwardcadet.bank.http

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import cats.data.Validated.{Invalid, Valid}
import io.edwardcadet.bank.actors.PersistentBankAccount.{BankAccount, Command, Response}
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.edwardcadet.bank.actors.PersistentBankAccount.Commands.{CreateBankAccount, GetBankAccount, UpdateBalance}
import io.edwardcadet.bank.actors.PersistentBankAccount.Responses.{BankAccountBalanceUpdatedResponse, BankAccountCreatedResponse, GetBankAccountResponse}
import io.edwardcadet.bank.http.Validation.{ValidationResult, Validator, required, validateEntity, validateMinimum, validateMinimumAbs, validateRequired}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import cats.implicits._

import scala.util.{Failure, Success}

class BankRouter(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {
  /*
  * POST /bank/
  * Payload: bank: bank account creation request as JSON
  * Response:
  *   201 Created
  *   Location: /bank/uuid
   */

  /*
  * GET /bank/uuid
  * Response:
  *   200 OK
  *   JSON repr of bank account details
  *   404 Not Found
   */

  /*
  * PUT /bank/uuid
  * Payload: (currency, amount) as JSON
  * Response:
  *   200 OK
  *   Payload: new bank details as JSON
  *   404 Not Found
  *   TODO 400 Bad Request
   */

  implicit val timeout: Timeout = Timeout(5.seconds)

  val routes: server.Route = pathPrefix("bank") {
    pathEndOrSingleSlash {
      post {
        // parse the payload
        entity(as[BankAccountCreationRequest]) { request =>
          // validation
          validateRequest(request) {
            /*
          * convert the request into a Command for the bank actor
          * send the command to the bank
          * expect a reply
          * send back an HTTP response
           */
            onSuccess(createBankAccount(request)) {
              case BankAccountCreatedResponse(id) =>
                respondWithHeader(Location(s"/bank/$id")) {
                  complete(StatusCodes.Created)
                }
            }
          }
        }
      }
    } ~
      path(Segment) { id =>
        get {
          /*
        * send command to the bank
        * expect a reply
        * send back the HTTP response
         */
          onSuccess(getBankAccount(id)) {
            case GetBankAccountResponse(Some(account)) =>
              complete(account) // 200 OK
            case GetBankAccountResponse(None) =>
              complete(StatusCodes.NotFound, FailureResponse(s"bank account $id not found"))
          }
        } ~
          put {
            /*
           * transform the request to a Command
           * send the command to the bank
           * expect a reply
           * send back an HTTPResponse
            */
            // validate the request
            entity(as[BankAccountUpdateRequest]) { request =>
              validateRequest(request) {
                onSuccess(updateBankAccount(id, request)) {
                  case BankAccountBalanceUpdatedResponse(Success(account)) =>
                    complete(account)
                  case BankAccountBalanceUpdatedResponse(Failure(ex)) =>
                    complete(StatusCodes.BadRequest, FailureResponse(s"${ex.getMessage}"))
                }
              }
            }
          }
      }
  }

  // exemples of Akka Ask Pattern
  // generalisable?
  def createBankAccount(request: BankAccountCreationRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(replyTo))

  def getBankAccount(id: String): Future[Response] =
    bank.ask(replyTo => GetBankAccount(id, replyTo))

  def updateBankAccount(id: String, request: BankAccountUpdateRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(id, replyTo))

  def validateRequest[R: Validator](request: R)(routeIfValid: Route): Route =
    validateEntity(request) match {
      case Valid(_) =>
        routeIfValid
      case Invalid(failures) =>
        complete(StatusCodes.BadRequest,
          FailureResponse(failures.toList.map(_.errorMessage).mkString(", ")))

    }
}

// DAO
// toCommand will translate the request to a command

case class BankAccountCreationRequest(user: String, currency: String, balance: Double) {
  def toCommand(replyTo: ActorRef[Response]): Command = CreateBankAccount(user,
    currency,
    balance,
    replyTo)
}

object BankAccountCreationRequest {
  implicit val validator: Validator[BankAccountCreationRequest] = (request: BankAccountCreationRequest) => {
    val userValidation = validateRequired("user", request.user)
    val currencyValidation = validateRequired("currency", request.currency)
    val balanceValidation = validateMinimum("balance", request.balance, 0)
      .combine(validateMinimumAbs("balance", request.balance, 0))

    (userValidation, currencyValidation, balanceValidation).mapN(BankAccountCreationRequest.apply)
  }
}

case class BankAccountUpdateRequest(currency: String, amount: Double) {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateBalance(id,
    currency,
    amount,
    replyTo)
}

object BankAccountUpdateRequest {
  implicit val validator: Validator[BankAccountUpdateRequest] = (request: BankAccountUpdateRequest) => {
    val currencyValidation = validateRequired("currency", request.currency)
    val amountValidation = validateMinimumAbs("amount", request.amount, 0.01)

    (currencyValidation, amountValidation).mapN(BankAccountUpdateRequest.apply)
  }
}

case class FailureResponse(reason: String)
