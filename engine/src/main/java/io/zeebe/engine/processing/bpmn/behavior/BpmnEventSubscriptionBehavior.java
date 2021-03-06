/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.bpmn.behavior;

import static io.zeebe.util.buffer.BufferUtil.bufferAsString;

import io.zeebe.engine.processing.bpmn.BpmnElementContext;
import io.zeebe.engine.processing.bpmn.BpmnProcessingException;
import io.zeebe.engine.processing.common.CatchEventBehavior;
import io.zeebe.engine.processing.common.EventTriggerBehavior;
import io.zeebe.engine.processing.common.ExpressionProcessor.EvaluationException;
import io.zeebe.engine.processing.common.Failure;
import io.zeebe.engine.processing.deployment.model.element.ExecutableActivity;
import io.zeebe.engine.processing.deployment.model.element.ExecutableBoundaryEvent;
import io.zeebe.engine.processing.deployment.model.element.ExecutableCatchEventSupplier;
import io.zeebe.engine.processing.deployment.model.element.ExecutableReceiveTask;
import io.zeebe.engine.processing.message.MessageCorrelationKeyException;
import io.zeebe.engine.processing.message.MessageNameException;
import io.zeebe.engine.processing.streamprocessor.sideeffect.SideEffects;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedCommandWriter;
import io.zeebe.engine.processing.variable.VariableBehavior;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.engine.state.instance.ElementInstance;
import io.zeebe.engine.state.instance.EventTrigger;
import io.zeebe.engine.state.instance.StoredRecord.Purpose;
import io.zeebe.engine.state.mutable.MutableElementInstanceState;
import io.zeebe.engine.state.mutable.MutableEventScopeInstanceState;
import io.zeebe.engine.state.mutable.MutableVariableState;
import io.zeebe.engine.state.mutable.MutableZeebeState;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import io.zeebe.protocol.record.value.ErrorType;
import io.zeebe.util.Either;

public final class BpmnEventSubscriptionBehavior {

  private static final String NO_PROCESS_FOUND_MESSAGE =
      "Expected to create an instance of process with key '%d', but no such process was found";

  private final ProcessInstanceRecord eventRecord = new ProcessInstanceRecord();

  private final BpmnStateBehavior stateBehavior;
  private final BpmnStateTransitionBehavior stateTransitionBehavior;
  private final MutableEventScopeInstanceState eventScopeInstanceState;
  private final MutableElementInstanceState elementInstanceState;
  private final CatchEventBehavior catchEventBehavior;

  private final StateWriter stateWriter;
  private final SideEffects sideEffects;

  private final KeyGenerator keyGenerator;
  private final ProcessState processState;
  private final MutableVariableState variablesState;
  private final TypedCommandWriter commandWriter;
  private final VariableBehavior variableBehavior;
  private final EventTriggerBehavior eventTriggerBehavior;

  public BpmnEventSubscriptionBehavior(
      final BpmnStateBehavior stateBehavior,
      final BpmnStateTransitionBehavior stateTransitionBehavior,
      final CatchEventBehavior catchEventBehavior,
      final VariableBehavior variableBehavior,
      final EventTriggerBehavior eventTriggerBehavior,
      final StateWriter stateWriter,
      final TypedCommandWriter commandWriter,
      final SideEffects sideEffects,
      final MutableZeebeState zeebeState) {
    this.stateBehavior = stateBehavior;
    this.stateTransitionBehavior = stateTransitionBehavior;
    this.catchEventBehavior = catchEventBehavior;
    this.variableBehavior = variableBehavior;
    this.eventTriggerBehavior = eventTriggerBehavior;
    this.stateWriter = stateWriter;
    this.commandWriter = commandWriter;
    this.sideEffects = sideEffects;

    processState = zeebeState.getProcessState();
    eventScopeInstanceState = zeebeState.getEventScopeInstanceState();
    elementInstanceState = zeebeState.getElementInstanceState();
    keyGenerator = zeebeState.getKeyGenerator();
    variablesState = zeebeState.getVariableState();
  }

  public <T extends ExecutableCatchEventSupplier> Either<Failure, Void> subscribeToEvents(
      final T element, final BpmnElementContext context) {

    try {
      catchEventBehavior.subscribeToEvents(context, element, sideEffects, commandWriter);
      return Either.right(null);

    } catch (final MessageCorrelationKeyException e) {
      return Either.left(
          new Failure(e.getMessage(), ErrorType.EXTRACT_VALUE_ERROR, e.getVariableScopeKey()));

    } catch (final EvaluationException e) {
      return Either.left(
          new Failure(
              e.getMessage(), ErrorType.EXTRACT_VALUE_ERROR, context.getElementInstanceKey()));
    } catch (final MessageNameException e) {
      return Either.left(e.getFailure());
    }
  }

  public void unsubscribeFromEvents(final BpmnElementContext context) {
    catchEventBehavior.unsubscribeFromEvents(context, commandWriter, sideEffects);
  }

  public void triggerBoundaryOrIntermediateEvent(
      final ExecutableReceiveTask element, final BpmnElementContext context) {

    eventTriggerBehavior.triggerEvent(
        context,
        eventTrigger -> {
          final boolean hasEventTriggeredForBoundaryEvent =
              element.getBoundaryEvents().stream()
                  .anyMatch(
                      boundaryEvent -> boundaryEvent.getId().equals(eventTrigger.getElementId()));

          if (hasEventTriggeredForBoundaryEvent) {
            return triggerBoundaryEvent(element, context, eventTrigger);

          } else {
            stateTransitionBehavior.transitionToCompleting(context);
            return context.getElementInstanceKey();
          }
        });
  }

  public void triggerBoundaryEvent(
      final ExecutableActivity element, final BpmnElementContext context) {
    eventTriggerBehavior.triggerEvent(
        context, eventTrigger -> triggerBoundaryEvent(element, context, eventTrigger));
  }

  private long triggerBoundaryEvent(
      final ExecutableActivity element,
      final BpmnElementContext context,
      final EventTrigger eventTrigger) {

    final var record =
        getEventRecord(context.getRecordValue(), eventTrigger, BpmnElementType.BOUNDARY_EVENT);

    final var boundaryEvent = getBoundaryEvent(element, context, eventTrigger);

    final long boundaryElementInstanceKey = keyGenerator.nextKey();
    if (boundaryEvent.interrupting()) {

      deferActivatingEvent(context, boundaryElementInstanceKey, record);

      stateTransitionBehavior.transitionToTerminating(context);

    } else {
      publishActivatingEvent(boundaryElementInstanceKey, record);
    }

    return boundaryElementInstanceKey;
  }

  private ProcessInstanceRecord getEventRecord(
      final ProcessInstanceRecord value,
      final EventTrigger event,
      final BpmnElementType elementType) {
    eventRecord.reset();
    eventRecord.wrap(value);
    eventRecord.setElementId(event.getElementId());
    eventRecord.setBpmnElementType(elementType);

    return eventRecord;
  }

  private <T extends ExecutableActivity> ExecutableBoundaryEvent getBoundaryEvent(
      final T element, final BpmnElementContext context, final EventTrigger eventTrigger) {

    return element.getBoundaryEvents().stream()
        .filter(boundaryEvent -> boundaryEvent.getId().equals(eventTrigger.getElementId()))
        .findFirst()
        .orElseThrow(
            () ->
                new BpmnProcessingException(
                    context,
                    String.format(
                        "Expected boundary event with id '%s' but not found.",
                        bufferAsString(eventTrigger.getElementId()))));
  }

  private void deferActivatingEvent(
      final BpmnElementContext context,
      final long eventElementInstanceKey,
      final ProcessInstanceRecord record) {

    elementInstanceState.storeRecord(
        eventElementInstanceKey,
        context.getElementInstanceKey(),
        record,
        ProcessInstanceIntent.ELEMENT_ACTIVATING,
        Purpose.DEFERRED);
  }

  public void publishTriggeredBoundaryEvent(final BpmnElementContext context) {
    publishTriggeredEvent(context, BpmnElementType.BOUNDARY_EVENT);
  }

  private void publishTriggeredEvent(
      final BpmnElementContext context, final BpmnElementType elementType) {
    elementInstanceState.getDeferredRecords(context.getElementInstanceKey()).stream()
        .filter(record -> record.getValue().getBpmnElementType() == elementType)
        .filter(record -> record.getState() == ProcessInstanceIntent.ELEMENT_ACTIVATING)
        .findFirst()
        .ifPresent(
            deferredRecord ->
                publishActivatingEvent(deferredRecord.getKey(), deferredRecord.getValue()));
  }

  private void publishActivatingEvent(
      final long elementInstanceKey, final ProcessInstanceRecord eventRecord) {

    stateWriter.appendFollowUpEvent(
        elementInstanceKey, ProcessInstanceIntent.ELEMENT_ACTIVATING, eventRecord);
  }

  public void triggerEventBasedGateway(final BpmnElementContext context) {

    final var eventTrigger =
        eventScopeInstanceState.peekEventTrigger(context.getElementInstanceKey());
    if (eventTrigger == null) {
      throw new BpmnProcessingException(
          context, "Expected event trigger for event-based gateway but not found.");
    }

    final var elementRecord =
        getEventRecord(
            context.getRecordValue(), eventTrigger, BpmnElementType.INTERMEDIATE_CATCH_EVENT);

    // the event trigger is deleted on transition to completed
    stateTransitionBehavior.transitionToCompleted(context);

    final var eventInstanceKey = keyGenerator.nextKey();
    // transition to activating and activated directly to pass the variables to this instance
    stateWriter.appendFollowUpEvent(
        eventInstanceKey, ProcessInstanceIntent.ELEMENT_ACTIVATING, elementRecord);
    stateWriter.appendFollowUpEvent(
        eventInstanceKey, ProcessInstanceIntent.ELEMENT_ACTIVATED, elementRecord);

    final var eventVariables = eventTrigger.getVariables();
    if (eventVariables.capacity() > 0) {
      // set as local variables of the element instance to use them for the variable output mapping
      variableBehavior.mergeLocalDocument(
          eventInstanceKey,
          context.getProcessDefinitionKey(),
          context.getProcessInstanceKey(),
          eventVariables);
    }

    commandWriter.appendFollowUpCommand(
        eventInstanceKey, ProcessInstanceIntent.COMPLETE_ELEMENT, elementRecord);
  }

  public boolean tryToActivateTriggeredStartEvent(final BpmnElementContext context) {
    final long processDefinitionKey = context.getProcessDefinitionKey();
    final long processInstanceKey = context.getProcessInstanceKey();

    final var process = processState.getProcessByKey(context.getProcessDefinitionKey());
    if (process == null) {
      // this should never happen because processes are never deleted.
      throw new BpmnProcessingException(
          context, String.format(NO_PROCESS_FOUND_MESSAGE, processDefinitionKey));
    }

    final var triggeredEvent = eventScopeInstanceState.peekEventTrigger(processDefinitionKey);
    if (triggeredEvent == null) {
      return false;
    }

    final var record =
        getEventRecord(context.getRecordValue(), triggeredEvent, BpmnElementType.START_EVENT)
            .setProcessInstanceKey(processInstanceKey)
            .setVersion(process.getVersion())
            .setBpmnProcessId(process.getBpmnProcessId())
            .setFlowScopeKey(processInstanceKey);

    final var newEventInstanceKey = keyGenerator.nextKey();
    commandWriter.appendFollowUpCommand(
        newEventInstanceKey, ProcessInstanceIntent.ACTIVATE_ELEMENT, record);

    variablesState.setTemporaryVariables(newEventInstanceKey, triggeredEvent.getVariables());

    eventScopeInstanceState.deleteTrigger(processDefinitionKey, triggeredEvent.getEventKey());
    return true;
  }

  public void publishTriggeredEventSubProcess(
      final boolean isChildMigrated, final BpmnElementContext context) {
    final var elementInstance = stateBehavior.getElementInstance(context);

    if (isInterrupted(isChildMigrated, elementInstance)) {
      elementInstanceState.getDeferredRecords(context.getElementInstanceKey()).stream()
          .filter(record -> record.getKey() == elementInstance.getInterruptingEventKey())
          .filter(
              record -> record.getValue().getBpmnElementType() == BpmnElementType.EVENT_SUB_PROCESS)
          .findFirst()
          .ifPresent(
              record -> {
                final var elementInstanceKey = record.getKey();
                final var interruptingRecord = record.getValue();

                stateWriter.appendFollowUpEvent(
                    elementInstanceKey,
                    ProcessInstanceIntent.ELEMENT_ACTIVATING,
                    interruptingRecord);
              });
    }
  }

  private boolean isInterrupted(
      final boolean isChildMigrated, final ElementInstance elementInstance) {
    final int expectedActiveInstanceCount = isChildMigrated ? 0 : 1;
    return elementInstance.getNumberOfActiveElementInstances() == expectedActiveInstanceCount
        && elementInstance.isInterrupted()
        && elementInstance.isActive();
  }
}
