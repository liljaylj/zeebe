/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.bpmn;

import io.zeebe.engine.Loggers;
import io.zeebe.engine.processing.bpmn.behavior.BpmnBehaviorsImpl;
import io.zeebe.engine.processing.bpmn.behavior.BpmnStateTransitionBehavior;
import io.zeebe.engine.processing.bpmn.behavior.TypedResponseWriterProxy;
import io.zeebe.engine.processing.bpmn.behavior.TypedStreamWriterProxy;
import io.zeebe.engine.processing.common.CatchEventBehavior;
import io.zeebe.engine.processing.common.EventTriggerBehavior;
import io.zeebe.engine.processing.common.ExpressionProcessor;
import io.zeebe.engine.processing.deployment.model.element.ExecutableFlowElement;
import io.zeebe.engine.processing.streamprocessor.MigratedStreamProcessors;
import io.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.zeebe.engine.processing.streamprocessor.TypedRecordProcessor;
import io.zeebe.engine.processing.streamprocessor.sideeffect.SideEffectProducer;
import io.zeebe.engine.processing.streamprocessor.sideeffect.SideEffectQueue;
import io.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.zeebe.engine.processing.variable.VariableBehavior;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.engine.state.mutable.MutableElementInstanceState;
import io.zeebe.engine.state.mutable.MutableZeebeState;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.record.intent.ProcessInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import java.util.function.Consumer;
import org.slf4j.Logger;

public final class BpmnStreamProcessor implements TypedRecordProcessor<ProcessInstanceRecord> {

  private static final Logger LOGGER = Loggers.PROCESS_PROCESSOR_LOGGER;

  private final TypedStreamWriterProxy streamWriterProxy = new TypedStreamWriterProxy();
  private final TypedResponseWriterProxy responseWriterProxy = new TypedResponseWriterProxy();
  private final SideEffectQueue sideEffectQueue = new SideEffectQueue();
  private final BpmnElementContextImpl context = new BpmnElementContextImpl();

  private final ProcessState processState;
  private final BpmnElementProcessors processors;
  private final ProcessInstanceStateTransitionGuard stateTransitionGuard;
  private final BpmnStateTransitionBehavior stateTransitionBehavior;
  private final MutableElementInstanceState elementInstanceState;

  public BpmnStreamProcessor(
      final ExpressionProcessor expressionProcessor,
      final CatchEventBehavior catchEventBehavior,
      final VariableBehavior variableBehavior,
      final EventTriggerBehavior eventTriggerBehavior,
      final MutableZeebeState zeebeState,
      final Writers writers) {
    processState = zeebeState.getProcessState();
    elementInstanceState = zeebeState.getElementInstanceState();

    final var bpmnBehaviors =
        new BpmnBehaviorsImpl(
            expressionProcessor,
            streamWriterProxy,
            responseWriterProxy,
            sideEffectQueue,
            zeebeState,
            catchEventBehavior,
            variableBehavior,
            eventTriggerBehavior,
            this::getContainerProcessor,
            writers);
    processors = new BpmnElementProcessors(bpmnBehaviors);

    stateTransitionGuard = bpmnBehaviors.stateTransitionGuard();
    stateTransitionBehavior = bpmnBehaviors.stateTransitionBehavior();
  }

  private BpmnElementContainerProcessor<ExecutableFlowElement> getContainerProcessor(
      final BpmnElementType elementType) {
    return processors.getContainerProcessor(elementType);
  }

  @Override
  public void processRecord(
      final TypedRecord<ProcessInstanceRecord> record,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffect) {

    // todo (#6202): replace writer proxies by Writers
    // initialize
    streamWriterProxy.wrap(streamWriter);
    responseWriterProxy.wrap(responseWriter, writer -> sideEffectQueue.add(writer::flush));
    sideEffectQueue.clear();
    sideEffect.accept(sideEffectQueue);

    final var intent = (ProcessInstanceIntent) record.getIntent();
    final var recordValue = record.getValue();

    context.init(record.getKey(), recordValue, intent);

    final var bpmnElementType = recordValue.getBpmnElementType();
    final var processor = processors.getProcessor(bpmnElementType);
    final ExecutableFlowElement element = getElement(recordValue, processor);

    // On migrating processors we saw issues on replay, where element instances are not existing on
    // replay/reprocessing. You need to know that migrated processors are only replayed, which means
    // the events are applied to the state and non migrated are reprocessed, so the processor is
    // called.
    //
    // If we have a non migrated type and reprocess that, we expect an existing element instance.
    // On normal processing this element instance was created by using the state writer of the
    // previous command/event most likely by an already migrated type. On replay this state writer
    // is not called which causes issues, like non existing element instance.
    //
    // To cover the gap between migrated and non migrated processors we need to re-create an element
    // instance here, such that we can continue in migrate the processors individually and still
    // are able to run the replay tests.
    final var instance = elementInstanceState.getInstance(context.getElementInstanceKey());
    if (instance == null
        && context.getIntent() == ProcessInstanceIntent.ELEMENT_ACTIVATING
        && !MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
      final var flowScopeInstance = elementInstanceState.getInstance(context.getFlowScopeKey());
      elementInstanceState.newInstance(
          flowScopeInstance,
          context.getElementInstanceKey(),
          context.getRecordValue(),
          ProcessInstanceIntent.ELEMENT_ACTIVATING);
    }

    // process the record
    if (stateTransitionGuard.isValidStateTransition(context)) {
      LOGGER.trace("Process process instance event [context: {}]", context);

      processEvent(intent, processor, element);
    }
  }

  private void processEvent(
      final ProcessInstanceIntent intent,
      final BpmnElementProcessor<ExecutableFlowElement> processor,
      final ExecutableFlowElement element) {

    switch (intent) {
      case ACTIVATE_ELEMENT:
        if (MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
          final var activatingContext = stateTransitionBehavior.transitionToActivating(context);
          stateTransitionBehavior.onElementActivating(element, activatingContext);
          processor.onActivate(element, activatingContext);
        } else {
          processor.onActivating(element, context);
        }
        break;
      case COMPLETE_ELEMENT:
        if (MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
          final var completingContext = stateTransitionBehavior.transitionToCompleting(context);
          processor.onComplete(element, completingContext);
        } else {
          processor.onCompleting(element, context);
        }
        break;
      case TERMINATE_ELEMENT:
        if (MigratedStreamProcessors.isMigrated(context.getBpmnElementType())) {
          final var terminatingContext = stateTransitionBehavior.transitionToTerminating(context);
          processor.onTerminate(element, terminatingContext);
        } else {
          processor.onTerminating(element, context);
        }
        break;
        // legacy behavior for not migrated processors
      case ELEMENT_ACTIVATING:
        processor.onActivating(element, context);
        break;
      case ELEMENT_ACTIVATED:
        processor.onActivated(element, context);
        break;
      case EVENT_OCCURRED:
        processor.onEventOccurred(element, context);
        break;
      case ELEMENT_COMPLETING:
        processor.onCompleting(element, context);
        break;
      case ELEMENT_COMPLETED:
        processor.onCompleted(element, context);
        break;
      case ELEMENT_TERMINATING:
        processor.onTerminating(element, context);
        break;
      case ELEMENT_TERMINATED:
        processor.onTerminated(element, context);
        break;
      case SEQUENCE_FLOW_TAKEN:
        // in order to keep the implementation simple, a sequence flow acts as an element that can
        // process `activating`
        processor.onActivating(element, context);
        break;
      default:
        throw new BpmnProcessingException(
            context,
            String.format(
                "Expected the processor '%s' to handle the event but the intent '%s' is not supported",
                processor.getClass(), intent));
    }
  }

  private ExecutableFlowElement getElement(
      final ProcessInstanceRecord recordValue,
      final BpmnElementProcessor<ExecutableFlowElement> processor) {

    return processState.getFlowElement(
        recordValue.getProcessDefinitionKey(),
        recordValue.getElementIdBuffer(),
        processor.getType());
  }
}
