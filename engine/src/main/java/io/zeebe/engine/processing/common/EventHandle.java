/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.common;

import io.zeebe.engine.processing.deployment.model.element.ExecutableFlowElement;
import io.zeebe.engine.processing.deployment.model.element.ExecutableStartEvent;
import io.zeebe.engine.processing.streamprocessor.MigratedStreamProcessors;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedCommandWriter;
import io.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.zeebe.engine.state.KeyGenerator;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.engine.state.instance.ElementInstance;
import io.zeebe.engine.state.mutable.MutableEventScopeInstanceState;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessEventRecord;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.record.intent.ProcessEventIntent;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import org.agrona.DirectBuffer;

public final class EventHandle {

  private final ProcessInstanceRecord recordForPICreation = new ProcessInstanceRecord();
  private final ProcessInstanceRecord eventOccurredRecord = new ProcessInstanceRecord();
  private final ProcessEventRecord processEventRecord = new ProcessEventRecord();

  private final KeyGenerator keyGenerator;
  private final MutableEventScopeInstanceState eventScopeInstanceState;

  private final TypedCommandWriter commandWriter;
  private final StateWriter stateWriter;
  private final ProcessState processState;
  private final EventTriggerBehavior eventTriggerBehavior;

  // TODO (saig0): use immutable states only (#6200)
  public EventHandle(
      final KeyGenerator keyGenerator,
      final MutableEventScopeInstanceState eventScopeInstanceState,
      final Writers writers,
      final ProcessState processState,
      final EventTriggerBehavior eventTriggerBehavior) {
    this.keyGenerator = keyGenerator;
    this.eventScopeInstanceState = eventScopeInstanceState;
    commandWriter = writers.command();
    stateWriter = writers.state();
    this.processState = processState;
    this.eventTriggerBehavior = eventTriggerBehavior;
  }

  public boolean canTriggerElement(final ElementInstance eventScopeInstance) {
    return eventScopeInstance != null
        && eventScopeInstance.isActive()
        && eventScopeInstanceState.isAcceptingEvent(eventScopeInstance.getKey());
  }

  /**
   * Triggers a process by updating the state with a new {@link ProcessEventIntent#TRIGGERED} event.
   *
   * <p>NOTE: this method assumes that the caller already verified that the target can accept new
   * events!
   *
   * @param eventScope the event's scope, whose key is used to index the trigger in {@link
   *     io.zeebe.engine.state.immutable.EventScopeInstanceState}
   * @param catchEventId the ID of the element which should be triggered by the event
   * @param variables the variables/payload of the event (can be empty)
   */
  public void triggerProcessEvent(
      final ElementInstance eventScope,
      final DirectBuffer catchEventId,
      final DirectBuffer variables) {
    final var newElementInstanceKey = keyGenerator.nextKey();
    processEventRecord.reset();
    processEventRecord
        .setScopeKey(eventScope.getKey())
        .setTargetElementIdBuffer(catchEventId)
        .setVariablesBuffer(variables)
        .setProcessDefinitionKey(eventScope.getValue().getProcessDefinitionKey())
        .setProcessInstanceKey(eventScope.getValue().getProcessInstanceKey());
    stateWriter.appendFollowUpEvent(
        newElementInstanceKey, ProcessEventIntent.TRIGGERED, processEventRecord);
  }

  public void activateElement(
      final ExecutableFlowElement catchEvent,
      final long eventScopeKey,
      final ProcessInstanceRecord elementRecord) {

    if (MigratedStreamProcessors.isMigrated(elementRecord.getBpmnElementType())) {

      if (catchEvent.getElementType() == BpmnElementType.INTERMEDIATE_CATCH_EVENT
          || catchEvent.getElementType() == BpmnElementType.RECEIVE_TASK
          || catchEvent.getElementType() == BpmnElementType.EVENT_BASED_GATEWAY) {
        commandWriter.appendFollowUpCommand(
            eventScopeKey, ProcessInstanceIntent.COMPLETE_ELEMENT, elementRecord);

      } else {
        commandWriter.appendNewCommand(ProcessInstanceIntent.ACTIVATE_ELEMENT, elementRecord);
      }

    } else {

      if (isEventSubprocess(catchEvent)) {
        final var executableStartEvent = (ExecutableStartEvent) catchEvent;

        eventTriggerBehavior.triggerEventSubProcess(
            executableStartEvent, eventScopeKey, elementRecord);
      } else {
        eventOccurredRecord.wrap(elementRecord);

        // TODO (saig0): don't write EVENT_OCCURRED when processors are migrated (#6187/#6196)
        stateWriter.appendFollowUpEvent(
            eventScopeKey, ProcessInstanceIntent.EVENT_OCCURRED, eventOccurredRecord);
      }
    }
  }

  public long triggerStartEvent(
      final long processDefinitionKey, final DirectBuffer elementId, final DirectBuffer variables) {

    final var newElementInstanceKey = keyGenerator.nextKey();
    final var triggered =
        eventScopeInstanceState.triggerEvent(
            processDefinitionKey, newElementInstanceKey, elementId, variables);

    if (triggered) {
      final var processInstanceKey = keyGenerator.nextKey();
      activateProcessInstanceForStartEvent(processDefinitionKey, processInstanceKey);
      return processInstanceKey;

    } else {
      return -1L;
    }
  }

  public void activateProcessInstanceForStartEvent(
      final long processDefinitionKey, final long processInstanceKey) {

    // todo: after migrating Process Processor we can write here the ACTIVATE command
    // https://github.com/camunda-cloud/zeebe/issues/6194

    final var process = processState.getProcessByKey(processDefinitionKey);

    recordForPICreation
        .setBpmnProcessId(process.getBpmnProcessId())
        .setProcessDefinitionKey(process.getKey())
        .setVersion(process.getVersion())
        .setProcessInstanceKey(processInstanceKey)
        .setElementId(process.getProcess().getId())
        .setBpmnElementType(process.getProcess().getElementType());

    stateWriter.appendFollowUpEvent(
        processInstanceKey, ProcessInstanceIntent.ELEMENT_ACTIVATING, recordForPICreation);
  }

  private boolean isEventSubprocess(final ExecutableFlowElement catchEvent) {
    return catchEvent instanceof ExecutableStartEvent
        && ((ExecutableStartEvent) catchEvent).getEventSubProcess() != null;
  }
}
