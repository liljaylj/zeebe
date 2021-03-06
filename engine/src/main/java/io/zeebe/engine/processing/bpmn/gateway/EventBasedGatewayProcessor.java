/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.bpmn.gateway;

import io.zeebe.engine.processing.bpmn.BpmnElementContext;
import io.zeebe.engine.processing.bpmn.BpmnElementProcessor;
import io.zeebe.engine.processing.bpmn.behavior.BpmnBehaviors;
import io.zeebe.engine.processing.bpmn.behavior.BpmnEventSubscriptionBehavior;
import io.zeebe.engine.processing.bpmn.behavior.BpmnIncidentBehavior;
import io.zeebe.engine.processing.bpmn.behavior.BpmnStateTransitionBehavior;
import io.zeebe.engine.processing.deployment.model.element.ExecutableEventBasedGateway;

public final class EventBasedGatewayProcessor
    implements BpmnElementProcessor<ExecutableEventBasedGateway> {

  private final BpmnStateTransitionBehavior stateTransitionBehavior;
  private final BpmnEventSubscriptionBehavior eventSubscriptionBehavior;
  private final BpmnIncidentBehavior incidentBehavior;

  public EventBasedGatewayProcessor(final BpmnBehaviors bpmnBehaviors) {
    stateTransitionBehavior = bpmnBehaviors.stateTransitionBehavior();
    eventSubscriptionBehavior = bpmnBehaviors.eventSubscriptionBehavior();
    incidentBehavior = bpmnBehaviors.incidentBehavior();
  }

  @Override
  public Class<ExecutableEventBasedGateway> getType() {
    return ExecutableEventBasedGateway.class;
  }

  @Override
  public void onActivate(
      final ExecutableEventBasedGateway element, final BpmnElementContext context) {

    eventSubscriptionBehavior
        .subscribeToEvents(element, context)
        .ifRightOrLeft(
            ok -> stateTransitionBehavior.transitionToActivated(context),
            failure -> incidentBehavior.createIncident(failure, context));
  }

  @Override
  public void onComplete(
      final ExecutableEventBasedGateway element, final BpmnElementContext context) {

    eventSubscriptionBehavior.unsubscribeFromEvents(context);

    // transition to completed and continue on the event of the gateway that was triggered
    // - according to the BPMN specification, the sequence flow to this event is not taken
    eventSubscriptionBehavior.triggerEventBasedGateway(context);
  }

  @Override
  public void onTerminate(
      final ExecutableEventBasedGateway element, final BpmnElementContext context) {

    eventSubscriptionBehavior.unsubscribeFromEvents(context);
    incidentBehavior.resolveIncidents(context);

    final var terminated = stateTransitionBehavior.transitionToTerminated(context);
    stateTransitionBehavior.onElementTerminated(element, terminated);
  }
}
