package io.edwardcadet.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

// a single bank account
class PersistentBankAccount {
  /*
    - fault tolerance
    - auditing
   */

  // commands = messages
  sealed trait Command

  case class CreateBankAccount(user: String,
                               currency: String,
                               initialBalance: Double,
                               replyTo: ActorRef[Response]) extends Command

  case class UpdateBalance(id: String,
                           currency: String,
                           amount: Double,
                           replyTo: ActorRef[Response]) extends Command

  case class GetBankAccount(id: String, replyTo: ActorRef[Response]) extends Command

  // events = to persist to Cassandra
  trait Event

  // would it be better to just use the account id?
  case class BankAccountCreated(bankAccount: BankAccount) extends Event

  // would it be better to just use the account id?
  case class BalanceUpdated(amount: Double) extends Event

  // state
  case class BankAccount(id: String, user: String, currency: String, balance: Double)


  // responses
  sealed trait Response

  case class BankAccountCreatedResponse(id: String) extends Response

  case class BankAccountBalanceUpdatedResponse(maybeBankAccount: Option[BankAccount]) extends Response

  case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount]) extends Response

  // command handler = message handler => persist an event
  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) =>
    command match {
      case CreateBankAccount(user, currency, initialBalance, replyTo) =>
        val id = state.id
        /*
        - bank creates
        - bank sends me CreateBankAccount
        - I persist BankAccountCreated
        - I update my state
        - reply back to bank with the BankAccountCreatedResponse
        - the bank surfaces the response to the HTTP server
         */
        Effect
          .persist(BankAccountCreated(BankAccount(id, user, currency, initialBalance)))
          .thenReply(replyTo)(_ => BankAccountCreatedResponse(id))

      case UpdateBalance(_, _, amount, replyTo) =>
        val newBalance = state.balance + amount

        // check here for withdrawal
        if (newBalance < 0) // illegal
          Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(None))
        else
          Effect
            .persist(BalanceUpdated(amount))
            .thenReply(replyTo)(newState => BankAccountBalanceUpdatedResponse(Some(newState)))

      case GetBankAccount(_, replyTo) =>
        Effect.reply(replyTo)(GetBankAccountResponse(Some(state)))
    }
  // event handler => update state
  val eventHandler: (BankAccount, Event) => BankAccount = ???
  // state


  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0.0), // unused
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}
