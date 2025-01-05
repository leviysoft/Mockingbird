package ru.tinkoff.tcb.utils

import eu.timepit.refined.api.Refined
import eu.timepit.refined.api.Validate
import eu.timepit.refined.refineV
import io.scalaland.chimney.PartialTransformer
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.partial

package object refinedchimney {
  implicit def extractRefined[Type, Refinement]: Transformer[Type Refined Refinement, Type] =
    _.value

  implicit def validateRefined[Type, Refinement](implicit
      validate: Validate.Plain[Type, Refinement]
  ): PartialTransformer[Type, Type Refined Refinement] =
    PartialTransformer[Type, Type Refined Refinement] { value =>
      partial.Result.fromEitherString(refineV[Refinement](value))
    }
}
