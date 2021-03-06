/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.nexmark.queries;

import org.apache.beam.sdk.nexmark.NexmarkConfiguration;
import org.apache.beam.sdk.nexmark.NexmarkUtils;
import org.apache.beam.sdk.nexmark.model.Auction;
import org.apache.beam.sdk.nexmark.model.Event;
import org.apache.beam.sdk.nexmark.model.IdNameReserve;
import org.apache.beam.sdk.nexmark.model.KnownSize;
import org.apache.beam.sdk.nexmark.model.Person;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.join.CoGbkResult;
import org.apache.beam.sdk.transforms.join.CoGroupByKey;
import org.apache.beam.sdk.transforms.join.KeyedPCollectionTuple;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;

/**
 * Query 8, 'Monitor New Users'. Select people who have entered the system and created auctions
 * in the last 12 hours, updated every 12 hours. In CQL syntax:
 *
 * <pre>
 * SELECT Rstream(P.id, P.name, A.reserve)
 * FROM Person [RANGE 12 HOUR] P, Auction [RANGE 12 HOUR] A
 * WHERE P.id = A.seller;
 * </pre>
 *
 * <p>To make things a bit more dynamic and easier to test we'll use a much shorter window.
 */
public class Query8 extends NexmarkQuery {
  public Query8(NexmarkConfiguration configuration) {
    super(configuration, "Query8");
  }

  private PCollection<IdNameReserve> applyTyped(PCollection<Event> events) {
    // Window and key new people by their id.
    PCollection<KV<Long, Person>> personsById =
        events
          .apply(JUST_NEW_PERSONS)
          .apply("Query8.WindowPersons",
            Window.<Person>into(
              FixedWindows.of(Duration.standardSeconds(configuration.windowSizeSec))))
            .apply("PersonById", PERSON_BY_ID);

    // Window and key new auctions by their id.
    PCollection<KV<Long, Auction>> auctionsBySeller =
        events.apply(JUST_NEW_AUCTIONS)
          .apply("Query8.WindowAuctions",
            Window.<Auction>into(
              FixedWindows.of(Duration.standardSeconds(configuration.windowSizeSec))))
            .apply("AuctionBySeller", AUCTION_BY_SELLER);

    // Join people and auctions and project the person id, name and auction reserve price.
    return KeyedPCollectionTuple.of(PERSON_TAG, personsById)
        .and(AUCTION_TAG, auctionsBySeller)
        .apply(CoGroupByKey.<Long>create())
        .apply(name + ".Select",
            ParDo.of(new DoFn<KV<Long, CoGbkResult>, IdNameReserve>() {
                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    Person person = c.element().getValue().getOnly(PERSON_TAG, null);
                    if (person == null) {
                      // Person was not created in last window period.
                      return;
                    }
                    for (Auction auction : c.element().getValue().getAll(AUCTION_TAG)) {
                      c.output(new IdNameReserve(person.id, person.name, auction.reserve));
                    }
                  }
                }));
  }

  @Override
  protected PCollection<KnownSize> applyPrim(PCollection<Event> events) {
    return NexmarkUtils.castToKnownSize(name, applyTyped(events));
  }
}
