/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.datatypes;

import java.util.Comparator;
import java.util.stream.Stream;

/** Description and metadata for a hard fork */
public interface HardforkId {

  /**
   * The name of the hard fork.
   *
   * @return the name for the fork
   */
  String name();

  /**
   * Has the fork been finalized? i.e., could the definition change in future versions of Besu?
   *
   * @return true if the specification is finalized.
   */
  boolean finalized();

  /**
   * A brief description of the hard fork, suitable for human consumption
   *
   * @return the description of the fork.
   */
  String description();

  /**
   * Indicates whether the blob schedule is mutable for this hard fork.
   *
   * @return true if the blob schedule is mutable, false otherwise.
   */
  boolean isBlobScheduleMutable();

  /** List of all Ethereum Mainnet hardforks, including future and developmental forks. */
  enum MainnetHardforkId implements HardforkId {
    /** Frontier fork. */
    FRONTIER(true, "Frontier", false),
    /** Homestead fork. */
    HOMESTEAD(true, "Homestead", false),
    /** DAO Recovery Init fork. */
    DAO_RECOVERY_INIT(true, "DAO Recovery Init", false),
    /** DAO Recovery Transition fork. */
    DAO_RECOVERY_TRANSITION(true, "DAO Recovery Transition", false),
    /** Tangerine Whistle fork. */
    TANGERINE_WHISTLE(true, "Tangerine Whistle", false),
    /** Spurious Dragon fork. */
    SPURIOUS_DRAGON(true, "Spurious Dragon", false),
    /** Byzantium fork. */
    BYZANTIUM(true, "Byzantium", false),
    /** Constantinople fork. */
    CONSTANTINOPLE(true, "Constantinople", false),
    /** Petersburg fork. */
    PETERSBURG(true, "Petersburg", false),
    /** Istanbul fork. */
    ISTANBUL(true, "Istanbul", false),
    /** Muir Glacier fork. */
    MUIR_GLACIER(true, "Muir Glacier", false),
    /** Berlin fork. */
    BERLIN(true, "Berlin", false),
    /** London fork. */
    LONDON(true, "London", false),
    /** Arrow Glacier fork. */
    ARROW_GLACIER(true, "Arrow Glacier", false),
    /** Gray Glacier fork. */
    GRAY_GLACIER(true, "Gray Glacier", false),
    /** Paris fork. */
    PARIS(true, "Paris", false),
    /** Shanghai fork. */
    SHANGHAI(true, "Shanghai", false),
    /** Cancun fork. */
    CANCUN(true, "Cancun", true),
    /** Cancun + EOF fork. */
    CANCUN_EOF(false, "Cancun + EOF", true),
    /** Prague fork. */
    PRAGUE(true, "Prague", true),
    /** Osaka fork. */
    OSAKA(true, "Osaka", false),
    /** BPO1 fork. */
    BPO1(true, "BPO1", true),
    /** BPO2 fork. */
    BPO2(true, "BPO2", true),
    /** BPO3 fork. */
    BPO3(true, "BPO3", true),
    /** BPO4 fork. */
    BPO4(true, "BPO4", true),
    /** BPO5 fork. */
    BPO5(true, "BPO5", true),
    /** Amsterdam fork. */
    AMSTERDAM(false, "Amsterdam", false),
    /** Bogota fork. */
    BOGOTA(false, "Bogota", false),
    /** Polis fork. (from the greek form of an earlier incarnation of the city of Istanbul. */
    POLIS(false, "Polis", false),
    /** Bangkok fork. */
    BANGKOK(false, "Bangkok", false),
    /** Development fork, for accepted and unscheduled EIPs. */
    FUTURE_EIPS(false, "FutureEips", false),
    /** Developmental fork, for experimental EIPs. */
    EXPERIMENTAL_EIPS(false, "ExperimentalEips", false);

    final boolean finalized;
    final String description;
    final boolean isBlobScheduleMutable;

    MainnetHardforkId(
        final boolean finalized, final String description, final boolean isBlobScheduleMutable) {
      this.finalized = finalized;
      this.description = description;
      this.isBlobScheduleMutable = isBlobScheduleMutable;
    }

    @Override
    public boolean finalized() {
      return finalized;
    }

    @Override
    public String description() {
      return description;
    }

    @Override
    public boolean isBlobScheduleMutable() {
      return isBlobScheduleMutable;
    }

    /**
     * The most recent finalized mainnet hardfork Besu supports. This will change across versions
     * and will be updated after mainnet activations.
     *
     * @return the most recently activated mainnet spec.
     */
    public static MainnetHardforkId mostRecent() {
      return Stream.of(MainnetHardforkId.values())
          .filter(MainnetHardforkId::finalized)
          .max(Comparator.naturalOrder())
          .orElseThrow();
    }
  }

  /** List of all Ethereum Classic hard forks. */
  enum ClassicHardforkId implements HardforkId {
    /** Frontier fork. */
    FRONTIER(true, "Frontier"),
    /** Homestead fork. */
    HOMESTEAD(true, "Homestead"),
    /** Classic Recovery Init fork. */
    CLASSIC_RECOVERY_INIT(true, "Classic Recovery Init"),
    /** Classic Tangerine Whistle fork. */
    CLASSIC_TANGERINE_WHISTLE(true, "Classic Tangerine Whistle"),
    /** Die Hard fork. */
    DIE_HARD(true, "Die Hard"),
    /** Gotham fork. */
    GOTHAM(true, "Gotham"),
    /** Defuse Difficulty Bomb fork. */
    DEFUSE_DIFFICULTY_BOMB(true, "Defuse Difficulty Bomb"),
    /** Atlantis fork. */
    ATLANTIS(true, "Atlantis"),
    /** Agharta fork. */
    AGHARTA(true, "Agharta"),
    /** Phoenix fork. */
    PHOENIX(true, "Phoenix"),
    /** Thanos fork. */
    THANOS(true, "Thanos"),
    /** Magneto fork. */
    MAGNETO(true, "Magneto"),
    /** Mystique fork. */
    MYSTIQUE(true, "Mystique"),
    /** Spiral fork. */
    SPIRAL(true, "Spiral");

    final boolean finalized;
    final String description;

    ClassicHardforkId(final boolean finalized, final String description) {
      this.finalized = finalized;
      this.description = description;
    }

    @Override
    public boolean finalized() {
      return finalized;
    }

    @Override
    public String description() {
      return description;
    }

    @Override
    public boolean isBlobScheduleMutable() {
      return false;
    }
  }
}
