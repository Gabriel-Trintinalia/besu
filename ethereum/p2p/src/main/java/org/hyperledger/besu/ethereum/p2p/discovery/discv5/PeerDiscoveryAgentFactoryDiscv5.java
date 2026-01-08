/*
 * Copyright contributors to Besu.
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
package org.hyperledger.besu.ethereum.p2p.discovery.discv5;

import org.hyperledger.besu.crypto.SignatureAlgorithm;
import org.hyperledger.besu.crypto.SignatureAlgorithmFactory;
import org.hyperledger.besu.cryptoservices.NodeKey;
import org.hyperledger.besu.ethereum.p2p.config.NetworkingConfiguration;
import org.hyperledger.besu.ethereum.p2p.discovery.DiscoveryPeer;
import org.hyperledger.besu.ethereum.p2p.discovery.NodeRecordManager;
import org.hyperledger.besu.ethereum.p2p.discovery.PeerDiscoveryAgent;
import org.hyperledger.besu.ethereum.p2p.discovery.PeerDiscoveryAgentFactory;
import org.hyperledger.besu.ethereum.p2p.rlpx.RlpxAgent;

import java.util.List;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import io.vertx.core.Vertx;
import org.ethereum.beacon.discovery.DiscoverySystemBuilder;
import org.ethereum.beacon.discovery.MutableDiscoverySystem;
import org.ethereum.beacon.discovery.schema.NodeRecord;
import org.ethereum.beacon.discovery.schema.NodeRecordFactory;

/** Minimal factory for DiscV5 PeerDiscoveryAgent using DiscoverySystemBuilder. */
@SuppressWarnings("UnusedVariable")
public final class PeerDiscoveryAgentFactoryDiscv5 implements PeerDiscoveryAgentFactory {
  private final List<NodeRecord> bootnodes;
  private final NetworkingConfiguration config;

  private final NodeRecordManager nodeRecordManager;

  private static final Supplier<SignatureAlgorithm> SIGNATURE_ALGORITHM =
      Suppliers.memoize(SignatureAlgorithmFactory::getInstance);

  private final NodeKey nodeKey;

  public PeerDiscoveryAgentFactoryDiscv5(
      final Vertx vertx,
      final NodeKey nodeKey,
      final NetworkingConfiguration config,
      final NodeRecordManager nodeRecordManager) {
    this.config = config;
    this.nodeKey = nodeKey;
    String bootnodeEnr =
        "enr:-Ke4QAr53_PK_q75A4VrRNlF0tTb5xpP-mu5tXfCxrpxQZbIcb1zmdZDCLNAjtTIoaM8ALvOQxbJHWsoa1qYsNvXb_GGAZD-7swXg2V0aMvKhMuiocCEaV2wV4JpZIJ2NIJpcISpm6kbiXNlY3AyNTZrMaEC0AjXyvYbypXWyL5lrz8inZKt-wFlVDbYv6BTupOctIiEc25hcMCDdGNwgnpHg3VkcIJ6Rw";
    this.nodeRecordManager = nodeRecordManager;

    List<String> mainnet =
        List.of(
            "enr:-Iu4QLm7bZGdAt9NSeJG0cEnJohWcQTQaI9wFLu3Q7eHIDfrI4cwtzvEW3F3VbG9XdFXlrHyFGeXPn9snTCQJ9bnMRABgmlkgnY0gmlwhAOTJQCJc2VjcDI1NmsxoQIZdZD6tDYpkpEfVo5bgiU8MGRjhcOmHGD2nErK0UKRrIN0Y3CCIyiDdWRwgiMo",
            "enr:-Iu4QEDJ4Wa_UQNbK8Ay1hFEkXvd8psolVK6OhfTL9irqz3nbXxxWyKwEplPfkju4zduVQj6mMhUCm9R2Lc4YM5jPcIBgmlkgnY0gmlwhANrfESJc2VjcDI1NmsxoQJCYz2-nsqFpeEj6eov9HSi9QssIVIVNr0I89J1vXM9foN0Y3CCIyiDdWRwgiMo",
            "enr:-Ku4QImhMc1z8yCiNJ1TyUxdcfNucje3BGwEHzodEZUan8PherEo4sF7pPHPSIB1NNuSg5fZy7qFsjmUKs2ea1Whi0EBh2F0dG5ldHOIAAAAAAAAAACEZXRoMpD1pf1CAAAAAP__________gmlkgnY0gmlwhBLf22SJc2VjcDI1NmsxoQOVphkDqal4QzPMksc5wnpuC3gvSC8AfbFOnZY_On34wIN1ZHCCIyg", // # 18.223.219.100 | aws-us-east-2-ohio
            "enr:-Ku4QP2xDnEtUXIjzJ_DhlCRN9SN99RYQPJL92TMlSv7U5C1YnYLjwOQHgZIUXw6c-BvRg2Yc2QsZxxoS_pPRVe0yK8Bh2F0dG5ldHOIAAAAAAAAAACEZXRoMpD1pf1CAAAAAP__________gmlkgnY0gmlwhBLf22SJc2VjcDI1NmsxoQMeFF5GrS7UZpAH2Ly84aLK-TyvH-dRo0JM1i8yygH50YN1ZHCCJxA", // # 18.223.219.100 | aws-us-east-2-ohio
            "enr:-Ku4QPp9z1W4tAO8Ber_NQierYaOStqhDqQdOPY3bB3jDgkjcbk6YrEnVYIiCBbTxuar3CzS528d2iE7TdJsrL-dEKoBh2F0dG5ldHOIAAAAAAAAAACEZXRoMpD1pf1CAAAAAP__________gmlkgnY0gmlwhBLf22SJc2VjcDI1NmsxoQMw5fqqkw2hHC4F5HZZDPsNmPdB1Gi8JPQK7pRc9XHh-oN1ZHCCKvg", // # 18.223.219.100 | aws-us-east-2-ohio
            "enr:-Le4QPUXJS2BTORXxyx2Ia-9ae4YqA_JWX3ssj4E_J-3z1A-HmFGrU8BpvpqhNabayXeOZ2Nq_sbeDgtzMJpLLnXFgAChGV0aDKQtTA_KgEAAAAAIgEAAAAAAIJpZIJ2NIJpcISsaa0Zg2lwNpAkAIkHAAAAAPA8kv_-awoTiXNlY3AyNTZrMaEDHAD2JKYevx89W0CcFJFiskdcEzkH_Wdv9iW42qLK79ODdWRwgiMohHVkcDaCI4I", // # 172.105.173.25 | linode-au-sydney      "enr:-Le4QLHZDSvkLfqgEo8IWGG96h6mxwe_PsggC20CL3neLBjfXLGAQFOPSltZ7oP6ol54OvaNqO02Rnvb8YmDR274uq8ChGV0aDKQtTA_KgEAAAAAIgEAAAAAAIJpZIJ2NIJpcISLosQxg2lwNpAqAX4AAAAAAPA8kv_-ax65iXNlY3AyNTZrMaEDBJj7_dLFACaxBfaI8KZTh_SSJUjhyAyfshimvSqo22WDdWRwgiMohHVkcDaCI4I # 139.162.196.49 | linode-uk-london
            "enr:-Le4QH6LQrusDbAHPjU_HcKOuMeXfdEB5NJyXgHWFadfHgiySqeDyusQMvfphdYWOzuSZO9Uq2AMRJR5O4ip7OvVma8BhGV0aDKQtTA_KgEAAAAAIgEAAAAAAIJpZIJ2NIJpcISLY9ncg2lwNpAkAh8AgQIBAAAAAAAAAAmXiXNlY3AyNTZrMaECDYCZTZEksF-kmgPholqgVt8IXr-8L7Nu7YrZ7HUpgxmDdWRwgiMohHVkcDaCI4I", // # 139.99.217.220 | ovh-au-sydney
            "enr:-Le4QIqLuWybHNONr933Lk0dcMmAB5WgvGKRyDihy1wHDIVlNuuztX62W51voT4I8qD34GcTEOTmag1bcdZ_8aaT4NUBhGV0aDKQtTA_KgEAAAAAIgEAAAAAAIJpZIJ2NIJpcISLY04ng2lwNpAkAh8AgAIBAAAAAAAAAA-fiXNlY3AyNTZrMaEDscnRV6n1m-D9ID5UsURk0jsoKNXt1TIrj8uKOGW6iluDdWRwgiMohHVkcDaCI4I", // # 139.99.78.39 | ovh-singapore
            "enr:-Ku4QHqVeJ8PPICcWk1vSn_XcSkjOkNiTg6Fmii5j6vUQgvzMc9L1goFnLKgXqBJspJjIsB91LTOleFmyWWrFVATGngBh2F0dG5ldHOIAAAAAAAAAACEZXRoMpC1MD8qAAAAAP__________gmlkgnY0gmlwhAMRHkWJc2VjcDI1NmsxoQKLVXFOhp2uX6jeT0DvvDpPcU8FWMjQdR4wMuORMhpX24N1ZHCCIyg", // # 3.17.30.69 | aws-us-east-2-ohio
            "enr:-Ku4QG-2_Md3sZIAUebGYT6g0SMskIml77l6yR-M_JXc-UdNHCmHQeOiMLbylPejyJsdAPsTHJyjJB2sYGDLe0dn8uYBh2F0dG5ldHOIAAAAAAAAAACEZXRoMpC1MD8qAAAAAP__________gmlkgnY0gmlwhBLY-NyJc2VjcDI1NmsxoQORcM6e19T1T9gi7jxEZjk_sjVLGFscUNqAY9obgZaxbIN1ZHCCIyg", // # 18.216.248.220 | aws-us-east-2-ohio
            "enr:-Ku4QPn5eVhcoF1opaFEvg1b6JNFD2rqVkHQ8HApOKK61OIcIXD127bKWgAtbwI7pnxx6cDyk_nI88TrZKQaGMZj0q0Bh2F0dG5ldHOIAAAAAAAAAACEZXRoMpC1MD8qAAAAAP__________gmlkgnY0gmlwhDayLMaJc2VjcDI1NmsxoQK2sBOLGcUb4AwuYzFuAVCaNHA-dy24UuEKkeFNgCVCsIN1ZHCCIyg", // # 54.178.44.198 | aws-ap-northeast-1-tokyo
            "enr:-Ku4QEWzdnVtXc2Q0ZVigfCGggOVB2Vc1ZCPEc6j21NIFLODSJbvNaef1g4PxhPwl_3kax86YPheFUSLXPRs98vvYsoBh2F0dG5ldHOIAAAAAAAAAACEZXRoMpC1MD8qAAAAAP__________gmlkgnY0gmlwhDZBrP2Jc2VjcDI1NmsxoQM6jr8Rb1ktLEsVcKAPa08wCsKUmvoQ8khiOl_SLozf9IN1ZHCCIyg", // # 54.65.172.253 | aws-ap-northeast-1-tokyo
            "enr:-LK4QA8FfhaAjlb_BXsXxSfiysR7R52Nhi9JBt4F8SPssu8hdE1BXQQEtVDC3qStCW60LSO7hEsVHv5zm8_6Vnjhcn0Bh2F0dG5ldHOIAAAAAAAAAACEZXRoMpC1MD8qAAAAAP__________gmlkgnY0gmlwhAN4aBKJc2VjcDI1NmsxoQJerDhsJ-KxZ8sHySMOCmTO6sHM3iCFQ6VMvLTe948MyYN0Y3CCI4yDdWRwgiOM", //
            "enr:-LK4QKWrXTpV9T78hNG6s8AM6IO4XH9kFT91uZtFg1GcsJ6dKovDOr1jtAAFPnS2lvNltkOGA9k29BUN7lFh_sjuc9QBh2F0dG5ldHOIAAAAAAAAAACEZXRoMpC1MD8qAAAAAP__________gmlkgnY0gmlwhANAdd-Jc2VjcDI1NmsxoQLQa6ai7y9PMN5hpLe5HmiJSlYzMuzP7ZhwRiwHvqNXdoN0Y3CCI4yDdWRwgiOM", // # 3.64.117.223 | aws-eu-central-1-frankfurt
            "enr:-IS4QPi-onjNsT5xAIAenhCGTDl4z-4UOR25Uq-3TmG4V3kwB9ljLTb_Kp1wdjHNj-H8VVLRBSSWVZo3GUe3z6k0E-IBgmlkgnY0gmlwhKB3_qGJc2VjcDI1NmsxoQMvAfgB4cJXvvXeM6WbCG86CstbSxbQBSGx31FAwVtOTYN1ZHCCIyg", //
            "enr:-KG4QPUf8-g_jU-KrwzG42AGt0wWM1BTnQxgZXlvCEIfTQ5hSmptkmgmMbRkpOqv6kzb33SlhPHJp7x4rLWWiVq5lSECgmlkgnY0gmlwhFPlR9KDaXA2kCoGxcAJAAAVAAAAAAAAABCJc2VjcDI1NmsxoQLdUv9Eo9sxCt0tc_CheLOWnX59yHJtkBSOL7kpxdJ6GYN1ZHCCIyiEdWRwNoIjKA");

    List<String> hoodi =
        List.of(
            "enr:-Mq4QLkmuSwbGBUph1r7iHopzRpdqE-gcm5LNZfcE-6T37OCZbRHi22bXZkaqnZ6XdIyEDTelnkmMEQB8w6NbnJUt9GGAZWaowaYh2F0dG5ldHOIABgAAAAAAACEZXRoMpDS8Zl_YAAJEAAIAAAAAAAAgmlkgnY0gmlwhNEmfKCEcXVpY4IyyIlzZWNwMjU2azGhA0hGa4jZJZYQAS-z6ZFK-m4GCFnWS8wfjO0bpSQn6hyEiHN5bmNuZXRzAIN0Y3CCIyiDdWRwgiMo",
            "enr:-Ku4QLVumWTwyOUVS4ajqq8ZuZz2ik6t3Gtq0Ozxqecj0qNZWpMnudcvTs-4jrlwYRQMQwBS8Pvtmu4ZPP2Lx3i2t7YBh2F0dG5ldHOIAAAAAAAAAACEZXRoMpBd9cEGEAAJEP__________gmlkgnY0gmlwhNEmfKCJc2VjcDI1NmsxoQLdRlI8aCa_ELwTJhVN8k7km7IDc3pYu-FMYBs5_FiigIN1ZHCCIyk",
            "enr:-LK4QAYuLujoiaqCAs0-qNWj9oFws1B4iy-Hff1bRB7wpQCYSS-IIMxLWCn7sWloTJzC1SiH8Y7lMQ5I36ynGV1ASj4Eh2F0dG5ldHOIYAAAAAAAAACEZXRoMpDS8Zl_YAAJEAAIAAAAAAAAgmlkgnY0gmlwhIbRilSJc2VjcDI1NmsxoQOmI5MlAu3f5WEThAYOqoygpS2wYn0XS5NV2aYq7T0a04N0Y3CCIyiDdWRwgiMo",
            "enr:-Ku4QIC89sMC0o-irosD4_23lJJ4qCGOvdUz7SmoShWx0k6AaxCFTKviEHa-sa7-EzsiXpDp0qP0xzX6nKdXJX3X-IQBh2F0dG5ldHOIAAAAAAAAAACEZXRoMpBd9cEGEAAJEP__________gmlkgnY0gmlwhIbRilSJc2VjcDI1NmsxoQK_m0f1DzDc9Cjrspm36zuRa7072HSiMGYWLsKiVSbP34N1ZHCCIyk",
            "enr:-Ku4QNkWjw5tNzo8DtWqKm7CnDdIq_y7xppD6c1EZSwjB8rMOkSFA1wJPLoKrq5UvA7wcxIotH6Usx3PAugEN2JMncIBh2F0dG5ldHOIAAAAAAAAAACEZXRoMpBd9cEGEAAJEP__________gmlkgnY0gmlwhIbHuBeJc2VjcDI1NmsxoQP3FwrhFYB60djwRjAoOjttq6du94DtkQuaN99wvgqaIYN1ZHCCIyk",
            "enr:-OS4QMJGE13xEROqvKN1xnnt7U-noc51VXyM6wFMuL9LMhQDfo1p1dF_zFdS4OsnXz_vIYk-nQWnqJMWRDKvkSK6_CwDh2F0dG5ldHOIAAAAADAAAACGY2xpZW502IpMaWdodGhvdXNljDcuMC4wLWJldGEuM4RldGgykNLxmX9gAAkQAAgAAAAAAACCaWSCdjSCaXCEhse4F4RxdWljgiMqiXNlY3AyNTZrMaECef77P8k5l3PC_raLw42OAzdXfxeQ-58BJriNaqiRGJSIc3luY25ldHMAg3RjcIIjKIN1ZHCCIyg",
            "enr:-LK4QDwhXMitMbC8xRiNL-XGMhRyMSOnxej-zGifjv9Nm5G8EF285phTU-CAsMHRRefZimNI7eNpAluijMQP7NDC8kEMh2F0dG5ldHOIAAAAAAAABgCEZXRoMpDS8Zl_YAAJEAAIAAAAAAAAgmlkgnY0gmlwhAOIT_SJc2VjcDI1NmsxoQMoHWNL4MAvh6YpQeM2SUjhUrLIPsAVPB8nyxbmckC6KIN0Y3CCIyiDdWRwgiMo",
            "enr:-LK4QPYl2HnMPQ7b1es6Nf_tFYkyya5bj9IqAKOEj2cmoqVkN8ANbJJJK40MX4kciL7pZszPHw6vLNyeC-O3HUrLQv8Mh2F0dG5ldHOIAAAAAAAAAMCEZXRoMpDS8Zl_YAAJEAAIAAAAAAAAgmlkgnY0gmlwhAMYRG-Jc2VjcDI1NmsxoQPQ35tjr6q1qUqwAnegQmYQyfqxC_6437CObkZneI9n34N0Y3CCIyiDdWRwgiMo",
            "enr:-KG4QKRSUi4IOAIK_xt5ERrwW_J47wmNCLWFh7Jo0hFE69drZsiZ5Pb5CEcM_njFTTLlIR6SCf67HTcSV1g6hCXdhWkCgmlkgnY0gmlwhLkvrBODaXA2kCoGxcAWAAAYAAAAAAAAABCJc2VjcDI1NmsxoQPU7g2jQGTz8BYbB2vLTb39S_PrcZAehwMM0b3bWsM5rIN1ZHCCIyiEdWRwNoIjKA");

    this.bootnodes =
        hoodi.stream()
            .map(
                enrString -> {
                  try {
                    return NodeRecordFactory.DEFAULT.fromEnr(enrString);
                  } catch (final IllegalArgumentException e) {
                    throw new RuntimeException("Invalid ENR bootnode: " + enrString, e);
                  }
                })
            .toList();
  }

  @Override
  public PeerDiscoveryAgent create(final RlpxAgent ignored) {
    final DiscoverySystemBuilder discoverySystemBuilder = new DiscoverySystemBuilder();
    nodeRecordManager.initializeLocalNode(
        config.getDiscovery().getAdvertisedHost(),
        config.getDiscovery().getBindPort(),
        config.getDiscovery().getBindPort());

    NodeRecord localNodeRecord =
        nodeRecordManager
            .getLocalNode()
            .flatMap(DiscoveryPeer::getNodeRecord)
            .orElseThrow(() -> new IllegalStateException("Local node record not initialized"));

    NodeKeyServiceDiscV5 nodeKeyService = new NodeKeyServiceDiscV5(nodeKey);
    final MutableDiscoverySystem discoverySystem =
        discoverySystemBuilder
            .listen(config.getDiscovery().getBindHost(), config.getDiscovery().getBindPort())
            .bootnodes(bootnodes)
            // .newAddressHandler(maybeUpdateNodeRecordHandler)
            .localNodeRecordListener(nodeRecordManager)
            .localNodeRecord(localNodeRecord)
            .nodeKeyService(nodeKeyService)

            // .addressAccessPolicy(
            //   discoConfig.areSiteLocalAddressesEnabled()
            //    ? AddressAccessPolicy.ALLOW_ALL
            //   : address -> !address.getAddress().isSiteLocalAddress())
            .buildMutable();
    return new DiscV5PeerDiscoveryAgent(discoverySystem, config);
  }
}
