package com.example.bank.api

import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowUpdateException
import io.temporal.failure.ApplicationFailure
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class ConflictException(message: String) : RuntimeException(message)

class NotFoundException(message: String) : RuntimeException(message)

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException) = problem(HttpStatus.BAD_REQUEST, e.message)

    /** Malformed JSON or a constructor check failing during deserialization (e.g. invalid simulator settings). */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) =
        problem(HttpStatus.BAD_REQUEST, (e.mostSpecificCause as? IllegalArgumentException)?.message ?: "Malformed request body")

    @ExceptionHandler(WorkflowExecutionAlreadyStarted::class)
    fun duplicate(e: WorkflowExecutionAlreadyStarted) =
        problem(HttpStatus.CONFLICT, "A transfer with this transfer ID already exists")

    @ExceptionHandler(WorkflowNotFoundException::class, NotFoundException::class)
    fun notFound(e: RuntimeException) = problem(HttpStatus.NOT_FOUND, e.message)

    /** The update validator rejected, e.g. because the transfer is not awaiting approval. */
    @ExceptionHandler(WorkflowUpdateException::class)
    fun updateRejected(e: WorkflowUpdateException): ProblemDetail {
        val cause = e.cause
        val message = if (cause is ApplicationFailure) cause.originalMessage else cause?.message ?: e.message
        return problem(HttpStatus.CONFLICT, message)
    }

    @ExceptionHandler(ConflictException::class)
    fun conflict(e: ConflictException) = problem(HttpStatus.CONFLICT, e.message)

    private fun problem(status: HttpStatus, detail: String?) = ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase)
}
