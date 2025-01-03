package ru.tinkoff.tcb.mockingbird.error

import ru.tinkoff.tcb.utils.id.SID

final case class SpawnError[E](source: SID[E], cause: Throwable) extends Exception(cause)
