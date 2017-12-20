/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.cloud;

import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.ObjectReleaseTracker;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreDescriptor;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZkShardTerms implements AutoCloseable{

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Object writingLock = new Object();
  private final AtomicInteger numWatcher = new AtomicInteger(0);
  private final String collection;
  private final String shard;
  private final String znodePath;
  private final SolrZkClient zkClient;
  private final Set<CoreTermWatcher> listeners = new HashSet<>();

  private Terms terms;

  interface CoreTermWatcher {
    // return true if the listener wanna to be triggered in the next time
    boolean onTermChanged(Terms terms);
  }

  public ZkShardTerms(String collection, String shard, SolrZkClient zkClient) {
    this.znodePath = ZkStateReader.COLLECTIONS_ZKNODE + "/" + collection + "/terms/" + shard;
    this.collection = collection;
    this.shard = shard;
    this.zkClient = zkClient;
    ensureTermNodeExist();
    updateTerms();
    ObjectReleaseTracker.track(this);
  }

  public boolean ensureTermsIsHigher(String leader, Set<String> replicasInLowerTerms) {
    Terms newTerms;
    while( (newTerms = terms.increaseTerms(leader, replicasInLowerTerms)) != null) {
      if (forceSaveTerms(newTerms)) return true;
    }
    return false;
  }

  public boolean canBecomeLeader(String coreNodeName) {
    return terms.canBecomeLeader(coreNodeName);
  }

  public void close() {
    // no watcher will be registered
    numWatcher.addAndGet(1);
    ObjectReleaseTracker.release(this);
  }

  // package private for testing, only used by tests
  Map<String, Long> getTerms() {
    synchronized (writingLock) {
      return new HashMap<>(terms.terms);
    }
  }

  void addListener(CoreTermWatcher listener) {
    synchronized (listeners) {
      listeners.add(listener);
    }
  }

  boolean removeTerm(CoreDescriptor cd) {
    synchronized (listeners) {
      // solrcore already closed
      listeners.removeIf(coreTermWatcher -> !coreTermWatcher.onTermChanged(terms));
    }
    Terms newTerms;
    while ( (newTerms = terms.removeTerm(cd.getCloudDescriptor().getCoreNodeName())) != null) {
      try {
        if (saveTerms(newTerms)) return newTerms.terms.isEmpty();
      } catch (KeeperException.NoNodeException e) {
        return true;
      }
    }
    return true;
  }

  void registerTerm(String replica) {
    Terms newTerms;
    while ( (newTerms = terms.registerTerm(replica)) != null) {
      if (forceSaveTerms(newTerms)) break;
    }
  }

  void setEqualsToMax(String replica) {
    Terms newTerms;
    while ( (newTerms = terms.setEqualsToMax(replica)) != null) {
      if (forceSaveTerms(newTerms)) break;
    }
  }

  int getNumListeners() {
    synchronized (listeners) {
      return listeners.size();
    }
  }

  private boolean forceSaveTerms(Terms newTerms) {
    try {
      return saveTerms(newTerms);
    } catch (KeeperException.NoNodeException e) {
      ensureTermNodeExist();
      return false;
    }
  }

  private boolean saveTerms(Terms newTerms) throws KeeperException.NoNodeException {
    byte[] znodeData = Utils.toJSON(newTerms.terms);
    // must retry on conn loss otherwise future election attempts may assume wrong LIR state
    try {
      Stat stat = zkClient.setData(znodePath, znodeData, newTerms.version, true);
      updateTerms(new Terms(newTerms.terms, stat.getVersion()));
      return true;
    } catch (KeeperException.BadVersionException e) {
      log.info("Failed to save terms, version is not match, retrying");
      updateTerms();
    } catch (KeeperException.NoNodeException e) {
      throw e;
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error save shard term for collection:" + collection, e);
    }
    return false;
  }


  private void ensureTermNodeExist() {
    String path = "/collections/"+collection+ "/terms";
    try {
      if (!zkClient.exists(path, true)) {
        try {
          zkClient.makePath(path, true);
        } catch (KeeperException.NodeExistsException e) {
          // it's okay if another beats us creating the node
        }
      }
      path += "/"+shard;
      if (!zkClient.exists(path, true)) {
        try {
          Map<String, Long> initialTerms = new HashMap<>();
          zkClient.create(path, Utils.toJSON(initialTerms), CreateMode.PERSISTENT, true);
        } catch (KeeperException.NodeExistsException e) {
          // it's okay if another beats us creating the node
        }
      }
    }  catch (InterruptedException e) {
      Thread.interrupted();
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error creating shard term node in Zookeeper for collection:" + collection, e);
    } catch (KeeperException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error creating shard term node in Zookeeper for collection:" + collection, e);
    }
  }

  private void updateTerms() {
    try {
      Watcher watcher = null;
      if (numWatcher.compareAndSet(0, 1)) {
        watcher = event -> {
          numWatcher.decrementAndGet();
          updateTerms();
        };
      }

      Stat stat = new Stat();
      byte[] data = zkClient.getData(znodePath, watcher, stat, true);
      Terms newTerms = new Terms((Map<String, Long>) Utils.fromJSON(data), stat.getVersion());
      updateTerms(newTerms);
    } catch (InterruptedException e) {
      Thread.interrupted();
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error updating shard term for collection:" + collection, e);
    } catch (KeeperException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error updating shard term for collection:" + collection, e);
    }
  }

  private void updateTerms(Terms newTerms) {
    boolean isChanged = false;
    synchronized (writingLock) {
      if (terms == null || newTerms.version != terms.version) {
        terms = newTerms;
        isChanged = true;
      }
    }
    if (isChanged) onTermUpdates(newTerms);
  }

  private void onTermUpdates(Terms newTerms) {
    synchronized (listeners) {
      listeners.removeIf(coreTermWatcher -> !coreTermWatcher.onTermChanged(newTerms));
    }
  }

  static class Terms {
    private final Map<String, Long> terms;
    private final int version;

    public Terms () {
      this(new HashMap<>(), 0);
    }

    public Terms(Map<String, Long> terms, int version) {
      this.terms = terms;
      this.version = version;
    }

    boolean canBecomeLeader(String coreNodeName) {
      if (terms.isEmpty()) return true;
      long maxTerm = Collections.max(terms.values());
      return terms.getOrDefault(coreNodeName, 0L) == maxTerm;
    }

    Long getTerm(String coreNodeName) {
      return terms.get(coreNodeName);
    }

    Terms increaseTerms(String leader, Set<String> replicasInLowerTerms) {
      if (!terms.containsKey(leader)) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Can not find leader's term " + leader);
      }

      boolean changed = false;
      boolean foundReplicasInLowerTerms = false;

      HashMap<String, Long> newValues = new HashMap<>(terms);
      long leaderTerm = newValues.get(leader);
      for (String replica : newValues.keySet()) {
        if (replicasInLowerTerms.contains(replica)) foundReplicasInLowerTerms = true;
        if (Objects.equals(newValues.get(replica), leaderTerm)) {
          if(replicasInLowerTerms.contains(replica)) {
            changed = true;
          } else {
            newValues.put(replica, leaderTerm+1);
          }
        }
      }

      // We should skip the optimization if there are no replicasInLowerTerms present in local terms,
      // this may indicate that the current value is stale
      if (!changed && foundReplicasInLowerTerms) return null;
      return new Terms(newValues, version);
    }

    Terms removeTerm(String coreNodeName) {
      if (!terms.containsKey(coreNodeName)) return null;

      HashMap<String, Long> newValues = new HashMap<>(terms);
      newValues.remove(coreNodeName);
      return new Terms(newValues, version);
    }

    Terms registerTerm(String coreNodeName) {
      if (terms.containsKey(coreNodeName)) return null;

      HashMap<String, Long> newValues = new HashMap<>(terms);
      newValues.put(coreNodeName, 0L);
      return new Terms(newValues, version);
    }

    Terms setEqualsToMax(String coreNodeName) {
      long maxTerm;
      try {
        maxTerm = Collections.max(terms.values());
      } catch (NoSuchElementException e){
        maxTerm = 0;
      }
      if (terms.get(coreNodeName) == maxTerm) return null;

      HashMap<String, Long> newValues = new HashMap<>(terms);
      newValues.put(coreNodeName, maxTerm);
      return new Terms(newValues, version);
    }
  }
}