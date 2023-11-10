/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.dataflow.orderbook;

import com.google.cloud.dataflow.orderbook.SessionContractKey.SessionContractKeyCoder;
import com.google.cloud.orderbook.model.MarketDepth;
import com.google.cloud.orderbook.model.OrderBookEvent;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.extensions.ordered.OrderedEventProcessor;
import org.apache.beam.sdk.extensions.ordered.OrderedEventProcessorResult;
import org.apache.beam.sdk.extensions.protobuf.ProtoCoder;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;

// TODO: better transform name (remove "transform") and decide if we shoudl use AutoValue approach to construct it.
public class OrderBookBuilderTransform extends
    PTransform<PCollection<OrderBookEvent>, OrderedEventProcessorResult<SessionContractKey, MarketDepth>> {

  private final int depth;
  private final boolean withTrade;

  private boolean produceStatusUpdatesOnEveryEvent = false;
  private int statusUpdateFrequency = 0;

  public OrderBookBuilderTransform(int depth, boolean withTrade) {
    this.depth = depth;
    this.withTrade = withTrade;
  }

  public OrderBookBuilderTransform produceStatusUpdatesOnEveryEvent() {
    this.produceStatusUpdatesOnEveryEvent = true;
    return this;
  }

  public OrderBookBuilderTransform produceStatusUpdatesInSeconds(int seconds) {
    this.statusUpdateFrequency = seconds;
    return this;
  }

  @Override
  public OrderedEventProcessorResult<SessionContractKey, MarketDepth> expand(
      PCollection<OrderBookEvent> input) {
    Coder<OrderBookEvent> eventCoder = ProtoCoder.of(OrderBookEvent.class);
    Coder<OrderBookMutableState> stateCoder = OrderBookCoder.of();
    Coder<SessionContractKey> keyCoder = SessionContractKeyCoder.of();
    Coder<MarketDepth> marketDepthCoder = ProtoCoder.of(MarketDepth.class);

    input.getPipeline().getCoderRegistry()
        .registerCoderForClass(SessionContractKey.class, SessionContractKeyCoder.of());

    OrderedEventProcessor<OrderBookEvent, SessionContractKey, MarketDepth, OrderBookMutableState> orderedProcessor =
        OrderedEventProcessor.create(new InitialStateCreator(depth, withTrade),
                new OrderBookEventExaminer(),
                eventCoder,
                stateCoder,
                keyCoder, marketDepthCoder)
            .withInitialSequence(0L)
            .withMaxResultsPerOutput(50000);
    if (produceStatusUpdatesOnEveryEvent) {
      orderedProcessor = orderedProcessor.produceStatusUpdatesOnEveryEvent(true);
    }

    orderedProcessor = orderedProcessor.withStatusUpdateFrequencySeconds(statusUpdateFrequency);

    return input
        .apply("Convert to KV", ParDo.of(new ConvertOrderToKV()))
        .apply("Produce OrderBook", orderedProcessor);
  }
}
