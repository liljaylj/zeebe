/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.state.appliers;

import io.zeebe.engine.state.TypedEventApplier;
import io.zeebe.engine.state.mutable.MutableEventScopeInstanceState;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessEventRecord;
import io.zeebe.protocol.record.intent.ProcessEventIntent;

final class ProcessEventTriggeredApplier
    implements TypedEventApplier<ProcessEventIntent, ProcessEventRecord> {
  private final MutableEventScopeInstanceState eventScopeState;

  public ProcessEventTriggeredApplier(final MutableEventScopeInstanceState eventScopeState) {
    this.eventScopeState = eventScopeState;
  }

  @Override
  public void applyState(final long key, final ProcessEventRecord value) {
    eventScopeState.triggerEvent(
        value.getScopeKey(), key, value.getTargetElementIdBuffer(), value.getVariablesBuffer());
  }
}
