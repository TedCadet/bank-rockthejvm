package io.edwardcadet.bank.http

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.util.Timeout
import io.edwardcadet.bank.actors.PersistentBankAccount.{BankAccount, Command, Response}
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.edwardcadet.bank.actors.PersistentBankAccount.Commands.{CreateBankAccount, GetBankAccount, UpdateBalance}
import io.edwardcadet.bank.actors.PersistentBankAccount.Responses.{BankAccountBalanceUpdatedResponse, BankAccountCreatedResponse, GetBankAccountResponse}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

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
            // TODO validate the request
            entity(as[BankAccountUpdateRequest]) { request =>
              onSuccess(updateBankAccount(id, request)) {
                case BankAccountBalanceUpdatedResponse(Some(account)) =>
                  complete(account)
                case BankAccountBalanceUpdatedResponse(None) =>
                  complete(StatusCodes.NotFound, FailureResponse(s"bank account $id not found"))
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
}

// used to receive a request from a consumer
// toCommand will translate the request to a command

case class BankAccountCreationRequest(user: String, currency: String, balance: Double) {
  def toCommand(replyTo: ActorRef[Response]): Command = CreateBankAccount(user,
    currency,
    balance,
    replyTo)
}

case class BankAccountUpdateRequest(currency: String, amount: Double) {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateBalance(id,
    currency,
    amount,
    replyTo)
}

case class FailureResponse(reason: String)
