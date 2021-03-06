/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.state.appliers;

import io.zeebe.engine.processing.deployment.model.element.ExecutableCatchEventElement;
import io.zeebe.engine.processing.deployment.model.element.ExecutableCatchEventSupplier;
import io.zeebe.engine.processing.deployment.model.element.ExecutableFlowElementContainer;
import io.zeebe.engine.processing.deployment.model.element.ExecutableFlowNode;
import io.zeebe.engine.processing.streamprocessor.MigratedStreamProcessors;
import io.zeebe.engine.state.TypedEventApplier;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.engine.state.instance.ElementInstance;
import io.zeebe.engine.state.mutable.MutableElementInstanceState;
import io.zeebe.engine.state.mutable.MutableEventScopeInstanceState;
import io.zeebe.engine.state.mutable.MutableVariableState;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import java.util.stream.IntStream;

/** Applies state changes for `ProcessInstance:Element_Activating` */
final class ProcessInstanceElementActivatingApplier
    implements TypedEventApplier<ProcessInstanceIntent, ProcessInstanceRecord> {

  private final MutableElementInstanceState elementInstanceState;
  private final MutableVariableState variableState;
  private final ProcessState processState;
  private final MutableEventScopeInstanceState eventScopeInstanceState;

  public ProcessInstanceElementActivatingApplier(
      final MutableElementInstanceState elementInstanceState,
      final ProcessState processState,
      final MutableVariableState variableState,
      final MutableEventScopeInstanceState eventScopeInstanceState) {
    this.elementInstanceState = elementInstanceState;
    this.processState = processState;
    this.variableState = variableState;
    this.eventScopeInstanceState = eventScopeInstanceState;
  }

  @Override
  public void applyState(final long elementInstanceKey, final ProcessInstanceRecord value) {

    createEventScope(elementInstanceKey, value);

    final var flowScopeInstance = elementInstanceState.getInstance(value.getFlowScopeKey());
    elementInstanceState.newInstance(
        flowScopeInstance, elementInstanceKey, value, ProcessInstanceIntent.ELEMENT_ACTIVATING);

    if (flowScopeInstance == null) {
      // process instance level
      return;
    }

    final var flowScopeElementType = flowScopeInstance.getValue().getBpmnElementType();
    final var currentElementType = value.getBpmnElementType();

    decrementActiveSequenceFlow(value, flowScopeInstance, flowScopeElementType, currentElementType);

    if (currentElementType == BpmnElementType.START_EVENT
        && flowScopeElementType == BpmnElementType.EVENT_SUB_PROCESS) {
      // the event variables are stored as temporary variables in the scope of the subprocess
      // - move them to the scope of the start event to apply the output variable mappings
      final var variables = variableState.getTemporaryVariables(flowScopeInstance.getKey());

      if (variables != null) {
        variableState.setTemporaryVariables(elementInstanceKey, variables);
        variableState.removeTemporaryVariables(flowScopeInstance.getKey());
      }
    }

    // manage the multi-instance loop counter
    if (flowScopeElementType == BpmnElementType.MULTI_INSTANCE_BODY
        && MigratedStreamProcessors.isMigrated(currentElementType)) {
      // update the loop counter of the multi-instance body (starting by 1)
      elementInstanceState.updateInstance(
          value.getFlowScopeKey(), ElementInstance::incrementMultiInstanceLoopCounter);

      // set the loop counter of the inner instance
      final var loopCounter = flowScopeInstance.getMultiInstanceLoopCounter();
      elementInstanceState.updateInstance(
          elementInstanceKey, instance -> instance.setMultiInstanceLoopCounter(loopCounter));
    }
  }

  private void decrementActiveSequenceFlow(
      final ProcessInstanceRecord value,
      final io.zeebe.engine.state.instance.ElementInstance flowScopeInstance,
      final BpmnElementType flowScopeElementType,
      final BpmnElementType currentElementType) {

    final boolean isEventSubProcess = currentElementType == BpmnElementType.EVENT_SUB_PROCESS;

    // We don't want to decrement the active sequence flow for elements which have no incoming
    // sequence flow and for interrupting event sub processes we reset the count completely.
    // Furthermore some elements are special and need to be handled separately.

    if (currentElementType != BpmnElementType.START_EVENT
        && currentElementType != BpmnElementType.BOUNDARY_EVENT
        && currentElementType != BpmnElementType.PARALLEL_GATEWAY
        && currentElementType != BpmnElementType.INTERMEDIATE_CATCH_EVENT
        && flowScopeElementType != BpmnElementType.MULTI_INSTANCE_BODY
        && !isEventSubProcess) {
      flowScopeInstance.decrementActiveSequenceFlows();
      elementInstanceState.updateInstance(flowScopeInstance);
    }

    if (currentElementType == BpmnElementType.INTERMEDIATE_CATCH_EVENT) {
      // If we are an intermediate catch event and our previous element is an event based gateway,
      // then we don't want to decrement the active flow, since based on the BPMN spec we DON'T take
      // the sequence flow.

      final var executableCatchEventElement =
          processState.getFlowElement(
              value.getProcessDefinitionKey(),
              value.getElementIdBuffer(),
              ExecutableCatchEventElement.class);

      final var incomingSequenceFlow = executableCatchEventElement.getIncoming().get(0);
      final var previousElement = incomingSequenceFlow.getSource();
      if (previousElement.getElementType() != BpmnElementType.EVENT_BASED_GATEWAY) {
        flowScopeInstance.decrementActiveSequenceFlows();
        elementInstanceState.updateInstance(flowScopeInstance);
      }
    }

    if (currentElementType == BpmnElementType.PARALLEL_GATEWAY) {
      // Parallel gateways can have more then one incoming sequence flow, we need to decrement the
      // active sequence flows based on the incoming count.

      final var executableFlowNode =
          processState.getFlowElement(
              value.getProcessDefinitionKey(),
              value.getElementIdBuffer(),
              ExecutableFlowNode.class);
      final var size = executableFlowNode.getIncoming().size();
      IntStream.range(0, size).forEach(i -> flowScopeInstance.decrementActiveSequenceFlows());
      elementInstanceState.updateInstance(flowScopeInstance);
    }

    if (isEventSubProcess) {
      // For interrupting event sub processes we need to reset the active sequence flows, because
      // we might have interrupted multiple sequence flows.
      // For non interrupting we do nothing, since we had no incoming sequence flow.

      final var executableFlowElementContainer = getExecutableFlowElementContainer(value);
      final var executableStartEvent = executableFlowElementContainer.getStartEvents().get(0);
      if (executableStartEvent.isInterrupting()) {
        flowScopeInstance.resetActiveSequenceFlows();
      }
      elementInstanceState.updateInstance(flowScopeInstance);
    }
  }

  private ExecutableFlowElementContainer getExecutableFlowElementContainer(
      final ProcessInstanceRecord value) {
    return processState.getFlowElement(
        value.getProcessDefinitionKey(),
        value.getElementIdBuffer(),
        ExecutableFlowElementContainer.class);
  }

  private void createEventScope(
      final long elementInstanceKey, final ProcessInstanceRecord elementRecord) {

    final var flowElement =
        processState.getFlowElement(
            elementRecord.getProcessDefinitionKey(),
            elementRecord.getElementIdBuffer(),
            ExecutableFlowNode.class);

    if (flowElement instanceof ExecutableCatchEventSupplier) {
      final var eventSupplier = (ExecutableCatchEventSupplier) flowElement;

      final var hasEvents = !eventSupplier.getEvents().isEmpty();
      if (hasEvents) {
        eventScopeInstanceState.createInstance(
            elementInstanceKey, eventSupplier.getInterruptingElementIds());
      }
    }
  }
}
