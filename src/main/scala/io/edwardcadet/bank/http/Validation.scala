package io.edwardcadet.bank.http

import cats.data.ValidatedNel
import cats.implicits._

object Validation {

  // minimum value
  trait Minimum[A] extends ((A, Double) => Boolean)

  trait MinimumAbs[A] extends ((A, Double) => Boolean)

  // TC instances
  // verifie si le value est plus grand que le minimum accepte
  implicit val minimumInt: Minimum[Int] = _ >= _
  implicit val minimumDouble: Minimum[Double] = _ >= _
  implicit val minimumIntAbs: MinimumAbs[Int] = Math.abs(_) >= _
  implicit val minimumDoubleAbs: MinimumAbs[Double] = Math.abs(_) >= _

  def minimum[A](value: A, minValue: Double)(implicit min: Minimum[A]): Boolean =
    min(value, minValue)

  def minimumAbs[A](value: A, minValue: Double)(implicit minAbs: MinimumAbs[A]): Boolean =
    minAbs(value, minValue)

  // field must be present
  trait Required[A] extends (A => Boolean)

  implicit val requiredString: Required[String] = _.nonEmpty

  def required[A](value: A)(implicit req: Required[A]): Boolean = req(value)

  // validation failures
  trait ValidationFailure {
    def errorMessage: String
  }

  case class EmptyField(fieldName: String) extends ValidationFailure {
    override def errorMessage: String = s"$fieldName is empty"
  }

  case class NegativeValue(fieldName: String) extends ValidationFailure {
    override def errorMessage: String = s"$fieldName is negative"
  }

  case class BelowMinimumValue(fieldName: String, min: Double) extends ValidationFailure {
    override def errorMessage: String = s"$fieldName is below the minimum value: $min"
  }

  // "main" API
  // Validated
  type ValidationResult[A] = ValidatedNel[ValidationFailure, A]

  def validateMinimum[A: Minimum](fieldName: String,
                                  value: A,
                                  minValue: Double): ValidationResult[A] = {
    if (minimum(value, minValue)) value.validNel
    else if (minValue == 0) NegativeValue(fieldName).invalidNel
    else BelowMinimumValue(fieldName, minValue).invalidNel
  }

  def validateMinimumAbs[A: MinimumAbs](fieldName: String,
                                        value: A,
                                        minValue: Double): ValidationResult[A] = {
    if (minimumAbs(value, minValue)) value.validNel
    else BelowMinimumValue(fieldName, minValue).invalidNel
  }

  def validateRequired[A: Required](fieldName: String, value: A): ValidationResult[A] = {
    if (required(value)) value.validNel
    else EmptyField(fieldName).invalidNel
  }

  trait Validator[A] {
    def validate(value: A): ValidationResult[A]
  }

  def validateEntity[A](value: A)(implicit validator: Validator[A]): ValidationResult[A] =
    validator.validate(value)
}
