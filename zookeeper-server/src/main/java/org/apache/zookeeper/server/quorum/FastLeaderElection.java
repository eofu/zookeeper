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

package org.apache.zookeeper.server.quorum;

import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.ZooKeeperThread;
import org.apache.zookeeper.server.quorum.QuorumCnxManager.Message;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.server.quorum.flexible.QuorumOracleMaj;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.apache.zookeeper.server.util.ZxidUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Implementation of leader election using TCP. It uses an object of the class
 * QuorumCnxManager to manage connections. Otherwise, the algorithm is push-based
 * as with the other UDP implementations.
 * <p>
 * There are a few parameters that can be tuned to change its behavior. First,
 * finalizeWait determines the amount of time to wait until deciding upon a leader.
 * This is part of the leader election algorithm.
 */

public class FastLeaderElection implements Election {

	/**
	 * Minimum notification interval, default is equal to finalizeWait
	 */
	public static final String MIN_NOTIFICATION_INTERVAL = "zookeeper.fastleader.minNotificationInterval";
	/**
	 * Maximum notification interval, default is 60s
	 */
	public static final String MAX_NOTIFICATION_INTERVAL = "zookeeper.fastleader.maxNotificationInterval";
	/**
	 * Determine how much time a process has to wait
	 * once it believes that it has reached the end of
	 * leader election.
	 */
	static final int finalizeWait = 200;
	private static final Logger LOG = LoggerFactory.getLogger(FastLeaderElection.class);
	static byte[] dummyData = new byte[0];
	/**
	 * Upper bound on the amount of time between two consecutive
	 * notification checks. This impacts the amount of time to get
	 * the system up again after long partitions. Currently 60 seconds.
	 */

	private static int maxNotificationInterval = 60000;
	/**
	 * Lower bound for notification check. The observer don't need to use
	 * the same lower bound as participant members
	 */
	private static int minNotificationInterval = finalizeWait;

	static {
		minNotificationInterval = Integer.getInteger(MIN_NOTIFICATION_INTERVAL, minNotificationInterval);
		LOG.info("{} = {} ms", MIN_NOTIFICATION_INTERVAL, minNotificationInterval);
		maxNotificationInterval = Integer.getInteger(MAX_NOTIFICATION_INTERVAL, maxNotificationInterval);
		LOG.info("{} = {} ms", MAX_NOTIFICATION_INTERVAL, maxNotificationInterval);
	}

	/**
	 * Connection manager. Fast leader election uses TCP for
	 * communication between peers, and QuorumCnxManager manages
	 * such connections.
	 */

	QuorumCnxManager manager;
	LinkedBlockingQueue<ToSend> sendqueue;
	LinkedBlockingQueue<Notification> recvqueue;
	QuorumPeer self;
	Messenger messenger;
	AtomicLong logicalclock = new AtomicLong(); /* Election instance */
	long proposedLeader;
	long proposedZxid;
	long proposedEpoch;
	volatile boolean stop;
	private SyncedLearnerTracker leadingVoteSet;

	/**
	 * Constructor of FastLeaderElection. It takes two parameters, one
	 * is the QuorumPeer object that instantiated this object, and the other
	 * is the connection manager. Such an object should be created only once
	 * by each peer during an instance of the ZooKeeper service.
	 *
	 * @param self    QuorumPeer that created this object
	 * @param manager Connection manager
	 */
	public FastLeaderElection(QuorumPeer self, QuorumCnxManager manager) {
		this.stop = false;
		this.manager = manager;
		starter(self, manager);
	}

	static ByteBuffer buildMsg(int state, long leader, long zxid, long electionEpoch, long epoch) {
		byte[] requestBytes = new byte[40];
		ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);

		/*
		 * Building notification packet to send, this is called directly only in tests
		 */

		requestBuffer.clear();
		requestBuffer.putInt(state);
		requestBuffer.putLong(leader);
		requestBuffer.putLong(zxid);
		requestBuffer.putLong(electionEpoch);
		requestBuffer.putLong(epoch);
		requestBuffer.putInt(0x1);

		return requestBuffer;
	}

	static ByteBuffer buildMsg(int state, long leader, long zxid, long electionEpoch, long epoch, byte[] configData) {
		byte[] requestBytes = new byte[44 + configData.length];
		ByteBuffer requestBuffer = ByteBuffer.wrap(requestBytes);

		/*
		 * Building notification packet to send
		 */

		requestBuffer.clear();
		requestBuffer.putInt(state);
		requestBuffer.putLong(leader);
		requestBuffer.putLong(zxid);
		requestBuffer.putLong(electionEpoch);
		requestBuffer.putLong(epoch);
		requestBuffer.putInt(Notification.CURRENTVERSION);
		requestBuffer.putInt(configData.length);
		requestBuffer.put(configData);

		return requestBuffer;
	}

	/**
	 * Returns the current value of the logical clock counter
	 */
	public long getLogicalClock() {
		return logicalclock.get();
	}

	/**
	 * This method is invoked by the constructor. Because it is a
	 * part of the starting procedure of the object that must be on
	 * any constructor of this class, it is probably best to keep as
	 * a separate method. As we have a single constructor currently,
	 * it is not strictly necessary to have it separate.
	 *
	 * @param self    QuorumPeer that created this object
	 * @param manager Connection manager
	 */
	private void starter(QuorumPeer self, QuorumCnxManager manager) {
		this.self = self;
		proposedLeader = -1;
		proposedZxid = -1;

		// 业务层发送队列，业务对象ToSend
		sendqueue = new LinkedBlockingQueue<ToSend>();
		// 业务层接收队列，业务对象Notification
		recvqueue = new LinkedBlockingQueue<Notification>();
		this.messenger = new Messenger(manager);
	}

	/**
	 * This method starts the sender and receiver threads.
	 */
	public void start() {
		this.messenger.start();
	}

	private void leaveInstance(Vote v) {
		LOG.debug(
				"About to leave FLE instance: leader={}, zxid=0x{}, my id={}, my state={}",
				v.getId(),
				Long.toHexString(v.getZxid()),
				self.getId(),
				self.getPeerState());
		recvqueue.clear();
	}

	public QuorumCnxManager getCnxManager() {
		return manager;
	}

	public void shutdown() {
		stop = true;
		proposedLeader = -1;
		proposedZxid = -1;
		leadingVoteSet = null;
		LOG.debug("Shutting down connection manager");
		manager.halt();
		LOG.debug("Shutting down messenger");
		messenger.halt();
		LOG.debug("FLE is down");
	}

	/**
	 * Send notifications to all peers upon a change in our vote
	 */
	private void sendNotifications() {
		// 循环发送
		for (long sid : self.getCurrentAndNextConfigVoters()) {
			QuorumVerifier qv = self.getQuorumVerifier();
			// 消息实体
			ToSend notmsg = new ToSend(
					ToSend.mType.notification,
					proposedLeader,
					proposedZxid,
					logicalclock.get(),
					QuorumPeer.ServerState.LOOKING,
					sid,
					proposedEpoch,
					qv.toString().getBytes(UTF_8));

			LOG.debug(
					"Sending Notification: {} (n.leader), 0x{} (n.zxid), 0x{} (n.round), {} (recipient),"
							+ " {} (myid), 0x{} (n.peerEpoch) ",
					proposedLeader,
					Long.toHexString(proposedZxid),
					Long.toHexString(logicalclock.get()),
					sid,
					self.getId(),
					Long.toHexString(proposedEpoch));

			// 添加到发送队列，这个队列会被workerSender消费
			sendqueue.offer(notmsg);
		}
	}

	/**
	 * Check if a pair (server id, zxid) succeeds our
	 * current vote.
	 */
	protected boolean totalOrderPredicate(long newId, long newZxid, long newEpoch, long curId, long curZxid, long curEpoch) {
		LOG.debug(
				"id: {}, proposed id: {}, zxid: 0x{}, proposed zxid: 0x{}",
				newId,
				curId,
				Long.toHexString(newZxid),
				Long.toHexString(curZxid));

		if (self.getQuorumVerifier().getWeight(newId) == 0) {
			return false;
		}

		/*
		 * We return true if one of the following three cases hold:
		 * 1- New epoch is higher
		 * 2- New epoch is the same as current epoch, but new zxid is higher
		 * 3- New epoch is the same as current epoch, new zxid is the same
		 *  as current zxid, but server id is higher.
		 */

		// 1、判断消息里的epoch是不是比当前的大，如果大则消息中的id对应的服务器就是leader
		// 2、如果epoch相等则判断zxid，如果消息里的zxid大，则消息中id对应的服务器就是leader
		// 3、如果前面两个都相等就比较服务器id，如果大就是leader
		return ((newEpoch > curEpoch)
				|| ((newEpoch == curEpoch)
				&& ((newZxid > curZxid)
				|| ((newZxid == curZxid)
				&& (newId > curId)))));
	}

	/**
	 * Given a set of votes, return the SyncedLearnerTracker which is used to
	 * determines if have sufficient to declare the end of the election round.
	 *
	 * @param votes Set of votes
	 * @param vote  Identifier of the vote received last
	 * @return the SyncedLearnerTracker with vote details
	 */
	protected SyncedLearnerTracker getVoteTracker(Map<Long, Vote> votes, Vote vote) {
		SyncedLearnerTracker voteSet = new SyncedLearnerTracker();
		voteSet.addQuorumVerifier(self.getQuorumVerifier());
		if (self.getLastSeenQuorumVerifier() != null
				&& self.getLastSeenQuorumVerifier().getVersion() > self.getQuorumVerifier().getVersion()) {
			voteSet.addQuorumVerifier(self.getLastSeenQuorumVerifier());
		}

		/*
		 * First make the views consistent. Sometimes peers will have different
		 * zxids for a server depending on timing.
		 */
		for (Map.Entry<Long, Vote> entry : votes.entrySet()) {
			if (vote.equals(entry.getValue())) {
				voteSet.addAck(entry.getKey());
			}
		}

		return voteSet;
	}

	/**
	 * In the case there is a leader elected, and a quorum supporting
	 * this leader, we have to check if the leader has voted and acked
	 * that it is leading. We need this check to avoid that peers keep
	 * electing over and over a peer that has crashed and it is no
	 * longer leading.
	 *
	 * @param votes         set of votes
	 * @param leader        leader id
	 * @param electionEpoch epoch id
	 */
	protected boolean checkLeader(Map<Long, Vote> votes, long leader, long electionEpoch) {

		boolean predicate = true;

		/*
		 * If everyone else thinks I'm the leader, I must be the leader.
		 * The other two checks are just for the case in which I'm not the
		 * leader. If I'm not the leader and I haven't received a message
		 * from leader stating that it is leading, then predicate is false.
		 */

		if (leader != self.getId()) {
			if (votes.get(leader) == null) {
				predicate = false;
			} else if (votes.get(leader).getState() != ServerState.LEADING) {
				predicate = false;
			}
		} else if (logicalclock.get() != electionEpoch) {
			predicate = false;
		}

		return predicate;
	}

	synchronized void updateProposal(long leader, long zxid, long epoch) {
		LOG.debug(
				"Updating proposal: {} (newleader), 0x{} (newzxid), {} (oldleader), 0x{} (oldzxid)",
				leader,
				Long.toHexString(zxid),
				proposedLeader,
				Long.toHexString(proposedZxid));

		proposedLeader = leader;
		proposedZxid = zxid;
		proposedEpoch = epoch;
	}

	public synchronized Vote getVote() {
		return new Vote(proposedLeader, proposedZxid, proposedEpoch);
	}

	/**
	 * A learning state can be either FOLLOWING or OBSERVING.
	 * This method simply decides which one depending on the
	 * role of the server.
	 *
	 * @return ServerState
	 */
	private ServerState learningState() {
		if (self.getLearnerType() == LearnerType.PARTICIPANT) {
			LOG.debug("I am a participant: {}", self.getId());
			return ServerState.FOLLOWING;
		} else {
			LOG.debug("I am an observer: {}", self.getId());
			return ServerState.OBSERVING;
		}
	}

	/**
	 * Returns the initial vote value of server identifier.
	 *
	 * @return long
	 */
	private long getInitId() {
		if (self.getQuorumVerifier().getVotingMembers().containsKey(self.getId())) {
			return self.getId();
		} else {
			return Long.MIN_VALUE;
		}
	}

	/**
	 * Returns initial last logged zxid.
	 *
	 * @return long
	 */
	private long getInitLastLoggedZxid() {
		if (self.getLearnerType() == LearnerType.PARTICIPANT) {
			return self.getLastLoggedZxid();
		} else {
			return Long.MIN_VALUE;
		}
	}

	/**
	 * Returns the initial vote value of the peer epoch.
	 *
	 * @return long
	 */
	private long getPeerEpoch() {
		if (self.getLearnerType() == LearnerType.PARTICIPANT) {
			try {
				return self.getCurrentEpoch();
			} catch (IOException e) {
				RuntimeException re = new RuntimeException(e.getMessage());
				re.setStackTrace(e.getStackTrace());
				throw re;
			}
		} else {
			return Long.MIN_VALUE;
		}
	}

	/**
	 * Update the peer state based on the given proposedLeader. Also update
	 * the leadingVoteSet if it becomes the leader.
	 */
	private void setPeerState(long proposedLeader, SyncedLearnerTracker voteSet) {
		ServerState ss = (proposedLeader == self.getId()) ? ServerState.LEADING : learningState();
		self.setPeerState(ss);
		if (ss == ServerState.LEADING) {
			leadingVoteSet = voteSet;
		}
	}

	/**
	 * Starts a new round of leader election. Whenever our QuorumPeer
	 * changes its state to LOOKING, this method is invoked, and it
	 * sends notifications to all other peers.
	 */
	public Vote lookForLeader() throws InterruptedException {
		try {
			self.jmxLeaderElectionBean = new LeaderElectionBean();
			MBeanRegistry.getInstance().register(self.jmxLeaderElectionBean, self.jmxLocalPeerBean);
		} catch (Exception e) {
			LOG.warn("Failed to register with JMX", e);
			self.jmxLeaderElectionBean = null;
		}

		self.start_fle = Time.currentElapsedTime();
		try {
			/*
			 * The votes from the current leader election are stored in recvset. In other words, a vote v is in recvset
			 * if v.electionEpoch == logicalclock. The current participant uses recvset to deduce on whether a majority
			 * of participants has voted for it.
			 */
			// 收到的投票
			Map<Long, Vote> recvset = new HashMap<Long, Vote>();

			/*
			 * The votes from previous leader elections, as well as the votes from the current leader election are
			 * stored in outofelection. Note that notifications in a LOOKING state are not stored in outofelection.
			 * Only FOLLOWING or LEADING notifications are stored in outofelection. The current participant could use
			 * outofelection to learn which participant is the leader if it arrives late (i.e., higher logicalclock than
			 * the electionEpoch of the received notifications) in a leader election.
			 */
			// 存储选举结果
			Map<Long, Vote> outofelection = new HashMap<Long, Vote>();

			int notTimeout = minNotificationInterval;

			synchronized (this) {
				logicalclock.incrementAndGet(); // 增加逻辑时钟
				// 自己的epoch和zxid
				updateProposal(getInitId(), getInitLastLoggedZxid(), getPeerEpoch());
			}

			LOG.info(
					"New election. My id = {}, proposed zxid=0x{}",
					self.getId(),
					Long.toHexString(proposedZxid));
			sendNotifications();// 发送投票，包括自己

			SyncedLearnerTracker voteSet = null;

			/*
			 * Loop in which we exchange notifications until we find a leader
			 */

			// 主循环，直到选举到leader
			while ((self.getPeerState() == ServerState.LOOKING) && (!stop)) {
				/*
				 * Remove next notification from queue, times out after 2 times
				 * the termination time
				 */
				// 从IO线程里拿到投票消息，自己的投票也在这里处理
				Notification n = recvqueue.poll(notTimeout, TimeUnit.MILLISECONDS);

				/*
				 * Sends more notifications if haven't received enough.
				 * Otherwise processes new notification.
				 */
				if (n == null) {
					// 消息发送完了继续发送，直到选出leader为止
					if (manager.haveDelivered()) {
						sendNotifications();
					} else {
						// 消息还没投递出去，可能是其他server还没启动，尝试再连接
						manager.connectAll();
					}

					/*
					 * Exponential backoff
					 */
					// 延长超时时间
					int tmpTimeOut = notTimeout * 2;
					notTimeout = Math.min(tmpTimeOut, maxNotificationInterval);

					/*
					 * When a leader failure happens on a master, the backup will be supposed to receive the honour from
					 * Oracle and become a leader, but the honour is likely to be delay. We do a re-check once timeout happens
					 *
					 * The leader election algorithm does not provide the ability of electing a leader from a single instance
					 * which is in a configuration of 2 instances.
					 * */
					self.getQuorumVerifier().revalidateVoteset(voteSet, notTimeout != minNotificationInterval);
					if (self.getQuorumVerifier() instanceof QuorumOracleMaj && voteSet != null && voteSet.hasAllQuorums() && notTimeout != minNotificationInterval) {
						setPeerState(proposedLeader, voteSet);
						Vote endVote = new Vote(proposedLeader, proposedZxid, logicalclock.get(), proposedEpoch);
						leaveInstance(endVote);
						return endVote;
					}

					LOG.info("Notification time out: {} ms", notTimeout);

				}
				// 收到了投票消息。判断收到的消息是不是属于这个集群内。
				else if (validVoter(n.sid) && validVoter(n.leader)) {
					/*
					 * Only proceed if the vote comes from a replica in the current or next
					 * voting view for a replica in the current or next voting view.
					 */
					// 判断收到消息的节点状态
					switch (n.state) {
						case LOOKING:
							if (getInitLastLoggedZxid() == -1) {
								LOG.debug("Ignoring notification as our zxid is -1");
								break;
							}
							if (n.zxid == -1) {
								LOG.debug("Ignoring notification from member with -1 zxid {}", n.sid);
								break;
							}
							// If notification > current, replace and send messages out
							// 大于，则表示是新一轮的选举
							if (n.electionEpoch > logicalclock.get()) {
								// 更新本地的logicalclock
								logicalclock.set(n.electionEpoch);
								// 清空接收队列
								recvset.clear();
								// 检查收到的这个消息是否可以胜出，一次比较epoch，zxid，myid
								if (totalOrderPredicate(n.leader, n.zxid, n.peerEpoch, getInitId(), getInitLastLoggedZxid(), getPeerEpoch())) {
									// 胜出后，把投票数据改为对方数据。
									updateProposal(n.leader, n.zxid, n.peerEpoch);
								}
								// 未胜出，票据不变
								else {
									updateProposal(getInitId(), getInitLastLoggedZxid(), getPeerEpoch());
								}
								// 广播消息，使其他节点知道我现在的数据
								sendNotifications();
							}
							// 小于，忽略这则消息
							else if (n.electionEpoch < logicalclock.get()) {
								LOG.debug(
										"Notification election epoch is smaller than logicalclock. n.electionEpoch = 0x{}, logicalclock=0x{}",
										Long.toHexString(n.electionEpoch),
										Long.toHexString(logicalclock.get()));
								break;
							}
							// 如果epoch相同，继续比较zxid，myid，如果胜出则更新票据，并且发出广播
							else if (totalOrderPredicate(n.leader, n.zxid, n.peerEpoch, proposedLeader, proposedZxid, proposedEpoch)) {
								updateProposal(n.leader, n.zxid, n.peerEpoch);
								sendNotifications();
							}

							LOG.debug(
									"Adding vote: from={}, proposed leader={}, proposed zxid=0x{}, proposed election epoch=0x{}",
									n.sid,
									n.leader,
									Long.toHexString(n.zxid),
									Long.toHexString(n.electionEpoch));

							// don't care about the version if it's in LOOKING state
							// 添加到本机投票集合，用来做选举终结判断
							recvset.put(n.sid, new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch));

							voteSet = getVoteTracker(recvset, new Vote(proposedLeader, proposedZxid, logicalclock.get(), proposedEpoch));

							// 判断选举是否结束，默认算法是超过半数的server同意
							if (voteSet.hasAllQuorums()) {

								// Verify if there is any change in the proposed leader
								// 一直等新的nofification到达，直到超时
								while ((n = recvqueue.poll(finalizeWait, TimeUnit.MILLISECONDS)) != null) {
									if (totalOrderPredicate(n.leader, n.zxid, n.peerEpoch, proposedLeader, proposedZxid, proposedEpoch)) {
										recvqueue.put(n);
										break;
									}
								}

								/*
								 * This predicate is true once we don't read any new
								 * relevant message from the reception queue
								 */
								// 确认leader
								if (n == null) {
									// 修改状态，leading或者following
									setPeerState(proposedLeader, voteSet);
									// 返回最终投票结果
									Vote endVote = new Vote(proposedLeader, proposedZxid, logicalclock.get(), proposedEpoch);
									leaveInstance(endVote);
									return endVote;
								}
							}
							break;
						// OBSERVING不参加选举
						case OBSERVING:
							LOG.debug("Notification from observer: {}", n.sid);
							break;

						/*
						 * In ZOOKEEPER-3922, we separate the behaviors of FOLLOWING and LEADING.
						 * To avoid the duplication of codes, we create a method called followingBehavior which was used to
						 * shared by FOLLOWING and LEADING. This method returns a Vote. When the returned Vote is null, it follows
						 * the original idea to break swtich statement; otherwise, a valid returned Vote indicates, a leader
						 * is generated.
						 *
						 * The reason why we need to separate these behaviors is to make the algorithm runnable for 2-node
						 * setting. An extra condition for generating leader is needed. Due to the majority rule, only when
						 * there is a majority in the voteset, a leader will be generated. However, in a configuration of 2 nodes,
						 * the number to achieve the majority remains 2, which means a recovered node cannot generate a leader which is
						 * the existed leader. Therefore, we need the Oracle to kick in this situation. In a two-node configuration, the Oracle
						 * only grants the permission to maintain the progress to one node. The oracle either grants the permission to the
						 * remained node and makes it a new leader when there is a faulty machine, which is the case to maintain the progress.
						 * Otherwise, the oracle does not grant the permission to the remained node, which further causes a service down.
						 *
						 * In the former case, when a failed server recovers and participate in the leader election, it would not locate a
						 * new leader because there does not exist a majority in the voteset. It fails on the containAllQuorum() infinitely due to
						 * two facts. First one is the fact that it does do not have a majority in the voteset. The other fact is the fact that
						 * the oracle would not give the permission since the oracle already gave the permission to the existed leader, the healthy machine.
						 * Logically, when the oracle replies with negative, it implies the existed leader which is LEADING notification comes from is a valid leader.
						 * To threat this negative replies as a permission to generate the leader is the purpose to separate these two behaviors.
						 *
						 *
						 * */
						case FOLLOWING:
							/*
							 * To avoid duplicate codes
							 * */
							Vote resultFN = receivedFollowingNotification(recvset, outofelection, voteSet, n);
							if (resultFN == null) {
								break;
							} else {
								return resultFN;
							}
						case LEADING:
							/*
							 * In leadingBehavior(), it performs followingBehvior() first. When followingBehavior() returns
							 * a null pointer, ask Oracle whether to follow this leader.
							 * */
							Vote resultLN = receivedLeadingNotification(recvset, outofelection, voteSet, n);
							if (resultLN == null) {
								break;
							} else {
								return resultLN;
							}
						default:
							LOG.warn("Notification state unrecognized: {} (n.state), {}(n.sid)", n.state, n.sid);
							break;
					}
				} else {
					if (!validVoter(n.leader)) {
						LOG.warn("Ignoring notification for non-cluster member sid {} from sid {}", n.leader, n.sid);
					}
					if (!validVoter(n.sid)) {
						LOG.warn("Ignoring notification for sid {} from non-quorum member sid {}", n.leader, n.sid);
					}
				}
			}
			return null;
		} finally {
			try {
				if (self.jmxLeaderElectionBean != null) {
					MBeanRegistry.getInstance().unregister(self.jmxLeaderElectionBean);
				}
			} catch (Exception e) {
				LOG.warn("Failed to unregister with JMX", e);
			}
			self.jmxLeaderElectionBean = null;
			LOG.debug("Number of connection processing threads: {}", manager.getConnectionThreadCount());
		}
	}

	private Vote receivedFollowingNotification(Map<Long, Vote> recvset, Map<Long, Vote> outofelection, SyncedLearnerTracker voteSet, Notification n) {
		/*
		 * Consider all notifications from the same epoch
		 * together.
		 */
		// 判断epoch是否相同
		if (n.electionEpoch == logicalclock.get()) {
			// 加入到本机的投票集合
			recvset.put(n.sid, new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch, n.state));
			voteSet = getVoteTracker(recvset, new Vote(n.version, n.leader, n.zxid, n.electionEpoch, n.peerEpoch, n.state));
			// 投票是否结束，如果结束，再确认LEADER是否有效
			if (voteSet.hasAllQuorums() && checkLeader(recvset, n.leader, n.electionEpoch)) {
				// 修改自己的状态
				setPeerState(n.leader, voteSet);
				Vote endVote = new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch);
				leaveInstance(endVote);
				return endVote;
			}
		}

		/*
		 * Before joining an established ensemble, verify that
		 * a majority are following the same leader.
		 *
		 * Note that the outofelection map also stores votes from the current leader election.
		 * See ZOOKEEPER-1732 for more information.
		 */
		outofelection.put(n.sid, new Vote(n.version, n.leader, n.zxid, n.electionEpoch, n.peerEpoch, n.state));
		voteSet = getVoteTracker(outofelection, new Vote(n.version, n.leader, n.zxid, n.electionEpoch, n.peerEpoch, n.state));

		if (voteSet.hasAllQuorums() && checkLeader(outofelection, n.leader, n.electionEpoch)) {
			synchronized (this) {
				logicalclock.set(n.electionEpoch);
				setPeerState(n.leader, voteSet);
			}
			Vote endVote = new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch);
			leaveInstance(endVote);
			return endVote;
		}

		return null;
	}

	private Vote receivedLeadingNotification(Map<Long, Vote> recvset, Map<Long, Vote> outofelection, SyncedLearnerTracker voteSet, Notification n) {
		/*
		 *
		 * In a two-node configuration, a recovery nodes cannot locate a leader because of the lack of the majority in the voteset.
		 * Therefore, it is the time for Oracle to take place as a tight breaker.
		 *
		 * */
		Vote result = receivedFollowingNotification(recvset, outofelection, voteSet, n);
		if (result == null) {
			/*
			 * Ask Oracle to see if it is okay to follow this leader.
			 *
			 * We don't need the CheckLeader() because itself cannot be a leader candidate
			 * */
			if (self.getQuorumVerifier().getNeedOracle() && !self.getQuorumVerifier().askOracle()) {
				LOG.info("Oracle indicates to follow");
				setPeerState(n.leader, voteSet);
				Vote endVote = new Vote(n.leader, n.zxid, n.electionEpoch, n.peerEpoch);
				leaveInstance(endVote);
				return endVote;
			} else {
				LOG.info("Oracle indicates not to follow");
				return null;
			}
		} else {
			return result;
		}
	}

	/**
	 * Check if a given sid is represented in either the current or
	 * the next voting view
	 *
	 * @param sid Server identifier
	 * @return boolean
	 */
	private boolean validVoter(long sid) {
		return self.getCurrentAndNextConfigVoters().contains(sid);
	}

	/**
	 * Notifications are messages that let other peers know that
	 * a given peer has changed its vote, either because it has
	 * joined leader election or because it learned of another
	 * peer with higher zxid or same zxid and higher server id
	 */

	public static class Notification {
		/*
		 * Format version, introduced in 3.4.6
		 */

		public static final int CURRENTVERSION = 0x2;
		int version;

		/*
		 * Proposed leader
		 */ long leader;

		/*
		 * zxid of the proposed leader
		 */ long zxid;

		/*
		 * Epoch
		 */ long electionEpoch;

		/*
		 * current state of sender
		 */ QuorumPeer.ServerState state;

		/*
		 * Address of sender
		 */ long sid;

		QuorumVerifier qv;
		/*
		 * epoch of the proposed leader
		 */ long peerEpoch;

	}

	/**
	 * Messages that a peer wants to send to other peers.
	 * These messages can be both Notifications and Acks
	 * of reception of notification.
	 */
	public static class ToSend {

		/*
		 * Proposed leader in the case of notification
		 */ long leader;
		/*
		 * id contains the tag for acks, and zxid for notifications
		 */ long zxid;
		/*
		 * Epoch
		 */ long electionEpoch;
		/*
		 * Current state;
		 */ QuorumPeer.ServerState state;
		/*
		 * Address of recipient
		 */ long sid;
		/*
		 * Used to send a QuorumVerifier (configuration info)
		 */ byte[] configData = dummyData;
		/*
		 * Leader epoch
		 */ long peerEpoch;

		ToSend(mType type, long leader, long zxid, long electionEpoch, ServerState state, long sid, long peerEpoch, byte[] configData) {

			this.leader = leader;
			this.zxid = zxid;
			this.electionEpoch = electionEpoch;
			this.state = state;
			this.sid = sid;
			this.peerEpoch = peerEpoch;
			this.configData = configData;
		}

		enum mType {
			crequest,
			challenge,
			notification,
			ack
		}

	}

	/**
	 * Multi-threaded implementation of message handler. Messenger
	 * implements two sub-classes: WorkReceiver and  WorkSender. The
	 * functionality of each is obvious from the name. Each of these
	 * spawns a new thread.
	 */

	protected class Messenger {

		WorkerSender ws;
		WorkerReceiver wr;
		Thread wsThread = null;
		Thread wrThread = null;

		/**
		 * Constructor of class Messenger.
		 *
		 * @param manager Connection manager
		 */
		Messenger(QuorumCnxManager manager) {

			this.ws = new WorkerSender(manager);

			this.wsThread = new Thread(this.ws, "WorkerSender[myid=" + self.getId() + "]");
			this.wsThread.setDaemon(true);

			this.wr = new WorkerReceiver(manager);

			this.wrThread = new Thread(this.wr, "WorkerReceiver[myid=" + self.getId() + "]");
			this.wrThread.setDaemon(true);
		}

		/**
		 * Starts instances of WorkerSender and WorkerReceiver
		 */
		void start() {
			// 启动业务层发送线程，将消息发送给IO负责类的QuorumCnxManager
			this.wsThread.start();
			// 启动业务接收线程，从IO负责类QuorumCnxManager接收消息
			this.wrThread.start();
		}

		/**
		 * Stops instances of WorkerSender and WorkerReceiver
		 */
		void halt() {
			this.ws.stop = true;
			this.wr.stop = true;
		}

		/**
		 * Receives messages from instance of QuorumCnxManager on
		 * method run(), and processes such messages.
		 */

		class WorkerReceiver extends ZooKeeperThread {

			volatile boolean stop;
			QuorumCnxManager manager;

			WorkerReceiver(QuorumCnxManager manager) {
				super("WorkerReceiver");
				this.stop = false;
				this.manager = manager;
			}

			public void run() {

				Message response;
				while (!stop) {
					// Sleeps on receive
					try {
						response = manager.pollRecvQueue(3000, TimeUnit.MILLISECONDS);
						if (response == null) {
							continue;
						}

						final int capacity = response.buffer.capacity();

						// The current protocol and two previous generations all send at least 28 bytes
						if (capacity < 28) {
							LOG.error("Got a short response from server {}: {}", response.sid, capacity);
							continue;
						}

						// this is the backwardCompatibility mode in place before ZK-107
						// It is for a version of the protocol in which we didn't send peer epoch
						// With peer epoch and version the message became 40 bytes
						boolean backCompatibility28 = (capacity == 28);

						// this is the backwardCompatibility mode for no version information
						boolean backCompatibility40 = (capacity == 40);

						response.buffer.clear();

						// Instantiate Notification and set its attributes
						Notification n = new Notification();

						int rstate = response.buffer.getInt();
						long rleader = response.buffer.getLong();
						long rzxid = response.buffer.getLong();
						long relectionEpoch = response.buffer.getLong();
						long rpeerepoch;

						int version = 0x0;
						QuorumVerifier rqv = null;

						try {
							if (!backCompatibility28) {
								rpeerepoch = response.buffer.getLong();
								if (!backCompatibility40) {
									/*
									 * Version added in 3.4.6
									 */

									version = response.buffer.getInt();
								} else {
									LOG.info("Backward compatibility mode (36 bits), server id: {}", response.sid);
								}
							} else {
								LOG.info("Backward compatibility mode (28 bits), server id: {}", response.sid);
								rpeerepoch = ZxidUtils.getEpochFromZxid(rzxid);
							}

							// check if we have a version that includes config. If so extract config info from message.
							if (version > 0x1) {
								int configLength = response.buffer.getInt();

								// we want to avoid errors caused by the allocation of a byte array with negative length
								// (causing NegativeArraySizeException) or huge length (causing e.g. OutOfMemoryError)
								if (configLength < 0 || configLength > capacity) {
									throw new IOException(String.format("Invalid configLength in notification message! sid=%d, capacity=%d, version=%d, configLength=%d",
											response.sid, capacity, version, configLength));
								}

								byte[] b = new byte[configLength];
								response.buffer.get(b);

								synchronized (self) {
									try {
										rqv = self.configFromString(new String(b, UTF_8));
										QuorumVerifier curQV = self.getQuorumVerifier();
										if (rqv.getVersion() > curQV.getVersion()) {
											LOG.info("{} Received version: {} my version: {}",
													self.getId(),
													Long.toHexString(rqv.getVersion()),
													Long.toHexString(self.getQuorumVerifier().getVersion()));
											if (self.getPeerState() == ServerState.LOOKING) {
												LOG.debug("Invoking processReconfig(), state: {}", self.getServerState());
												self.processReconfig(rqv, null, null, false);
												if (!rqv.equals(curQV)) {
													LOG.info("restarting leader election");
													self.shuttingDownLE = true;
													self.getElectionAlg().shutdown();

													break;
												}
											} else {
												LOG.debug("Skip processReconfig(), state: {}", self.getServerState());
											}
										}
									} catch (IOException | ConfigException e) {
										LOG.error("Something went wrong while processing config received from {}", response.sid);
									}
								}
							} else {
								LOG.info("Backward compatibility mode (before reconfig), server id: {}", response.sid);
							}
						} catch (BufferUnderflowException | IOException e) {
							LOG.warn("Skipping the processing of a partial / malformed response message sent by sid={} (message length: {})",
									response.sid, capacity, e);
							continue;
						}
						/*
						 * If it is from a non-voting server (such as an observer or
						 * a non-voting follower), respond right away.
						 */
						if (!validVoter(response.sid)) {
							Vote current = self.getCurrentVote();
							QuorumVerifier qv = self.getQuorumVerifier();
							ToSend notmsg = new ToSend(
									ToSend.mType.notification,
									current.getId(),
									current.getZxid(),
									logicalclock.get(),
									self.getPeerState(),
									response.sid,
									current.getPeerEpoch(),
									qv.toString().getBytes(UTF_8));

							sendqueue.offer(notmsg);
						} else {
							// Receive new message
							LOG.debug("Receive new notification message. My id = {}", self.getId());

							// State of peer that sent this message
							QuorumPeer.ServerState ackstate = QuorumPeer.ServerState.LOOKING;
							switch (rstate) {
								case 0:
									ackstate = QuorumPeer.ServerState.LOOKING;
									break;
								case 1:
									ackstate = QuorumPeer.ServerState.FOLLOWING;
									break;
								case 2:
									ackstate = QuorumPeer.ServerState.LEADING;
									break;
								case 3:
									ackstate = QuorumPeer.ServerState.OBSERVING;
									break;
								default:
									continue;
							}

							n.leader = rleader;
							n.zxid = rzxid;
							n.electionEpoch = relectionEpoch;
							n.state = ackstate;
							n.sid = response.sid;
							n.peerEpoch = rpeerepoch;
							n.version = version;
							n.qv = rqv;
							/*
							 * Print notification info
							 */
							LOG.info(
									"Notification: my state:{}; n.sid:{}, n.state:{}, n.leader:{}, n.round:0x{}, "
											+ "n.peerEpoch:0x{}, n.zxid:0x{}, message format version:0x{}, n.config version:0x{}",
									self.getPeerState(),
									n.sid,
									n.state,
									n.leader,
									Long.toHexString(n.electionEpoch),
									Long.toHexString(n.peerEpoch),
									Long.toHexString(n.zxid),
									Long.toHexString(n.version),
									(n.qv != null ? (Long.toHexString(n.qv.getVersion())) : "0"));

							/*
							 * If this server is looking, then send proposed leader
							 */

							if (self.getPeerState() == QuorumPeer.ServerState.LOOKING) {
								recvqueue.offer(n);

								/*
								 * Send a notification back if the peer that sent this
								 * message is also looking and its logical clock is
								 * lagging behind.
								 */
								if ((ackstate == QuorumPeer.ServerState.LOOKING)
										&& (n.electionEpoch < logicalclock.get())) {
									Vote v = getVote();
									QuorumVerifier qv = self.getQuorumVerifier();
									ToSend notmsg = new ToSend(
											ToSend.mType.notification,
											v.getId(),
											v.getZxid(),
											logicalclock.get(),
											self.getPeerState(),
											response.sid,
											v.getPeerEpoch(),
											qv.toString().getBytes());
									sendqueue.offer(notmsg);
								}
							} else {
								/*
								 * If this server is not looking, but the one that sent the ack
								 * is looking, then send back what it believes to be the leader.
								 */
								Vote current = self.getCurrentVote();
								if (ackstate == QuorumPeer.ServerState.LOOKING) {
									if (self.leader != null) {
										if (leadingVoteSet != null) {
											self.leader.setLeadingVoteSet(leadingVoteSet);
											leadingVoteSet = null;
										}
										self.leader.reportLookingSid(response.sid);
									}


									LOG.debug(
											"Sending new notification. My id ={} recipient={} zxid=0x{} leader={} config version = {}",
											self.getId(),
											response.sid,
											Long.toHexString(current.getZxid()),
											current.getId(),
											Long.toHexString(self.getQuorumVerifier().getVersion()));

									QuorumVerifier qv = self.getQuorumVerifier();
									ToSend notmsg = new ToSend(
											ToSend.mType.notification,
											current.getId(),
											current.getZxid(),
											current.getElectionEpoch(),
											self.getPeerState(),
											response.sid,
											current.getPeerEpoch(),
											qv.toString().getBytes());
									sendqueue.offer(notmsg);
								}
							}
						}
					} catch (InterruptedException e) {
						LOG.warn("Interrupted Exception while waiting for new message", e);
					}
				}
				LOG.info("WorkerReceiver is down");
			}

		}

		/**
		 * This worker simply dequeues a message to send and
		 * and queues it on the manager's queue.
		 */

		class WorkerSender extends ZooKeeperThread {

			volatile boolean stop;
			QuorumCnxManager manager;

			WorkerSender(QuorumCnxManager manager) {
				super("WorkerSender");
				this.stop = false;
				this.manager = manager;
			}

			public void run() {
				while (!stop) {
					try {
						// 从队列中获取消息实体
						ToSend m = sendqueue.poll(3000, TimeUnit.MILLISECONDS);
						if (m == null) {
							continue;
						}

						process(m);
					} catch (InterruptedException e) {
						break;
					}
				}
				LOG.info("WorkerSender is down");
			}

			/**
			 * Called by run() once there is a new message to send.
			 *
			 * @param m message to send
			 */
			void process(ToSend m) {
				ByteBuffer requestBuffer = buildMsg(m.state.ordinal(), m.leader, m.zxid, m.electionEpoch, m.peerEpoch, m.configData);

				manager.toSend(m.sid, requestBuffer);

			}

		}

	}

}
