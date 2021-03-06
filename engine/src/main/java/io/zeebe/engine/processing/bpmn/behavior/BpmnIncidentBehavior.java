/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.bpmn.behavior;

import io.zeebe.engine.processing.bpmn.BpmnElementContext;
import io.zeebe.engine.processing.common.Failure;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedCommandWriter;
import io.zeebe.engine.state.immutable.IncidentState;
import io.zeebe.engine.state.mutable.MutableZeebeState;
import io.zeebe.protocol.impl.record.value.incident.IncidentRecord;
import io.zeebe.protocol.record.intent.IncidentIntent;

public final class BpmnIncidentBehavior {

  private final IncidentRecord incidentCommand = new IncidentRecord();

  private final IncidentState incidentState;
  private final StateWriter stateWriter;
  private final TypedCommandWriter typedCommandWriter;

  public BpmnIncidentBehavior(
      final MutableZeebeState zeebeState,
      final TypedCommandWriter typedCommandWriter,
      final StateWriter stateWriter) {
    incidentState = zeebeState.getIncidentState();
    this.stateWriter = stateWriter;
    this.typedCommandWriter = typedCommandWriter;
  }

  public void resolveJobIncident(final long jobKey) {
    final long incidentKey = incidentState.getJobIncidentKey(jobKey);
    final boolean hasIncident = incidentKey != IncidentState.MISSING_INCIDENT;

    if (hasIncident) {
      final IncidentRecord incidentRecord = incidentState.getIncidentRecord(incidentKey);
      stateWriter.appendFollowUpEvent(incidentKey, IncidentIntent.RESOLVED, incidentRecord);
    }
  }

  public void createIncident(final Failure failure, final BpmnElementContext context) {
    final var variableScopeKey =
        failure.getVariableScopeKey() > 0
            ? failure.getVariableScopeKey()
            : context.getElementInstanceKey();

    incidentCommand.reset();
    incidentCommand
        .setProcessInstanceKey(context.getProcessInstanceKey())
        .setBpmnProcessId(context.getBpmnProcessId())
        .setProcessDefinitionKey(context.getProcessDefinitionKey())
        .setElementInstanceKey(context.getElementInstanceKey())
        .setElementId(context.getElementId())
        .setVariableScopeKey(variableScopeKey)
        .setErrorType(failure.getErrorType())
        .setErrorMessage(failure.getMessage());

    typedCommandWriter.appendNewCommand(IncidentIntent.CREATE, incidentCommand);
  }

  public void resolveIncidents(final BpmnElementContext context) {
    incidentState.forExistingProcessIncident(
        context.getElementInstanceKey(),
        (record, key) -> stateWriter.appendFollowUpEvent(key, IncidentIntent.RESOLVED, record));
  }
}
