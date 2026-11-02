package com.example.bank.domain.account

import com.fraktalio.fmodel.domain.IDecider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals

/**
 * Given/When/Then for deciders:
 *
 * ```
 * accountDecider.given(opened, credited(100))
 *     .whenCommand(Debit(...))
 *     .thenEvents(Debited(...))
 * ```
 */
fun <C, S, E> IDecider<C, S, E>.given(vararg events: E) = GivenEvents(this, events.toList())

class GivenEvents<C, S, E>(private val decider: IDecider<C, S, E>, private val events: List<E>) {
    fun whenCommand(command: C): Then<E> {
        val state = events.fold(decider.initialState, decider.evolve)
        val newEvents = runBlocking { decider.decide(command, state).toList() }
        return Then(newEvents)
    }
}

class Then<E>(val actual: List<E>) {
    fun thenEvents(vararg expected: E) = assertEquals(expected.toList(), actual)

    fun thenNothing() = assertEquals(emptyList(), actual)
}
