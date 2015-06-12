/*
 * Copyright (c) 2011-2014 Pivotal Software, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package reactor.rx.action.combination;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Dispatcher;
import reactor.fn.Function;
import reactor.fn.tuple.Tuple;
import reactor.rx.subscription.PushSubscription;

import java.util.Arrays;
import java.util.List;

/**
 * @author Stephane Maldini
 * @since 2.0
 */
public final class ZipAction<O, V, TUPLE extends Tuple>
		extends FanInAction<O, ZipAction.Zippable<O>, V, ZipAction.InnerSubscriber<O, V>> {

	private static final Object EMPTY_ZIPPED_DATA = new Object();

	final Function<TUPLE, ? extends V> accumulator;

	int index = 0;
	int count = 0;

	Object[] toZip = new Object[1];

	@SuppressWarnings("unchecked")
	public static <TUPLE extends Tuple, V> Function<TUPLE, List<V>> joinZipper() {
		return new Function<TUPLE, List<V>>() {
			@Override
			public List<V> apply(TUPLE ts) {
				return Arrays.asList((V[]) ts.toArray());
			}
		};
	}

	public ZipAction(Dispatcher dispatcher,
	                 Function<TUPLE, ? extends V> accumulator, Iterable<? extends Publisher<? extends O>>
			                 composables) {
		super(dispatcher, composables);
		this.accumulator = accumulator;
	}

	@SuppressWarnings("unchecked")
	protected void broadcastTuple(boolean isFinishing) {
		long capacity = this.capacity;
		if (count >= capacity) {

			if (!checkAllFilled()) return;

			count = 0;
			Object[] _toZip = toZip;
			toZip = new Object[toZip.length];

			V res = accumulator.apply((TUPLE) Tuple.of(_toZip));

			if (res != null) {
				broadcastNext(res);

				if (!isFinishing && upstreamSubscription.pendingRequestSignals() > 0) {
					dispatcher.dispatch(capacity, upstreamSubscription, null);
				}
			}
		}
		if (isFinishing) {
			broadcastComplete();
		}
	}

	private boolean checkAllFilled() {
		for (int i = 0; i < toZip.length; i++) {
			if (toZip[i] == null) {
				return false;
			}
		}
		return true;
	}


	@Override
	protected FanInSubscription<O, Zippable<O>, V, InnerSubscriber<O, V>> createFanInSubscription() {
		return new ZipSubscription(this);
	}

	@Override
	protected PushSubscription<Zippable<O>> createTrackingSubscription(Subscription subscription) {
		return innerSubscriptions;
	}

	@Override
	protected void doNext(Zippable<O> ev) {
		count++;
		toZip[ev.index] = ev.data == null ? EMPTY_ZIPPED_DATA : ev.data;

		broadcastTuple(false);
	}

	@Override
	protected void doComplete() {
		broadcastTuple(true);
	}

	@Override
	protected InnerSubscriber<O, V> createSubscriber() {
		return new ZipAction.InnerSubscriber<>(this, index++);
	}

	@Override
	protected long initUpstreamPublisherAndCapacity() {
		long i = 0l;
		for (Publisher<? extends O> composable : publishers) {
			addPublisher(composable);
			i++;
		}
		return i;
	}

	@Override
	public String toString() {
		String formatted = super.toString();
		for (int i = 0; i < toZip.length; i++) {
			if (toZip[i] != null)
				formatted += "(" + (i) + "):" + toZip[i] + ",";
		}
		return formatted.substring(0, count > 0 ? formatted.length() - 1 : formatted.length());
	}

//Handling each new Publisher to zip

	public static final class InnerSubscriber<O, V> extends FanInAction.InnerSubscriber<O, Zippable<O>, V> {
		final ZipAction<O, V, ?> outerAction;
		final int                index;

		InnerSubscriber(ZipAction<O, V, ?> outerAction, int index) {
			super(outerAction);
			this.index = index;
			this.outerAction = outerAction;
		}

		@Override
		@SuppressWarnings("unchecked")
		public void onSubscribe(Subscription subscription) {
			setSubscription(new FanInSubscription.InnerSubscription<O, Zippable<O>, InnerSubscriber<O, V>>(subscription,
					this));
			int newSize = outerAction.innerSubscriptions.runningComposables;
			newSize = newSize == 0 ? 1 : newSize;
			outerAction.capacity(newSize);

			if (newSize > outerAction.toZip.length) {
				Object[] previousZip = outerAction.toZip;
				outerAction.toZip = new Object[newSize];
				System.arraycopy(previousZip, 0, outerAction.toZip, 0, newSize - 1);
			}

			if (pendingRequests > 0) {
				request(1);
			}
			if (outerAction.dynamicMergeAction != null) {
				outerAction.dynamicMergeAction.decrementWip();
			}
		}

		@Override
		public void onComplete() {
			if (TERMINATE_UPDATER.compareAndSet(this, 0, 1)) {
				outerAction.innerSubscriptions.remove(sequenceId);
				outerAction.status.compareAndSet(RUNNING, COMPLETING);
				if (0 == FanInSubscription.RUNNING_COMPOSABLE_UPDATER.decrementAndGet(outerAction.innerSubscriptions)
				&& outerAction.toZip[index] == null) {
					outerAction.innerSubscriptions.serialComplete();
				}

			}
		}

		@Override
		public void request(long n) {
			super.request(1);
		}

		@Override
		public void onNext(O ev) {
			if (--pendingRequests > 0) pendingRequests = 0l;
			//emittedSignals++;
			long nextCount = outerAction.count + 1;
			outerAction.innerSubscriptions.serialNext(new Zippable<O>(index, ev));

			if(outerAction.status.get() == COMPLETING){
				int nextRunning = FanInSubscription.RUNNING_COMPOSABLE_UPDATER.get(outerAction.innerSubscriptions);
				if (nextCount == nextRunning
						&& TERMINATE_UPDATER.compareAndSet(this, 0, 1)) {
					outerAction.innerSubscriptions.remove(sequenceId);
					nextRunning = FanInSubscription.RUNNING_COMPOSABLE_UPDATER.decrementAndGet(outerAction.innerSubscriptions);
					if ( 0 == nextRunning) {
						outerAction.innerSubscriptions.serialComplete();
					}
				}
			}
		}


		@Override
		public boolean isReactivePull(Dispatcher dispatcher, long producerCapacity) {
			return true;
		}

		@Override
		public long getCapacity() {
			return 1;
		}

		@Override
		public String toString() {
			return "Zip.InnerSubscriber{index=" + index + ", " +
					"pending=" + pendingRequests + ", emitted=" + emittedSignals + "}";
		}
	}

	private final class ZipSubscription extends FanInSubscription<O, Zippable<O>, V, ZipAction.InnerSubscriber<O, V>> {

		public ZipSubscription(Subscriber<? super Zippable<O>> subscriber) {
			super(subscriber);
		}

		@Override
		public boolean shouldRequestPendingSignals() {
			synchronized (this) {
				return pendingRequestSignals > 0 && pendingRequestSignals != Long.MAX_VALUE && count == maxCapacity;
			}
		}

		@Override
		public void request(long elements) {
			if (pendingRequestSignals == Long.MAX_VALUE) {
				super.parallelRequest(1);
			} else {
				super.request(elements);
			}
		}
	}

	public static final class Zippable<O> {
		final int index;
		final O   data;

		public Zippable(int index, O data) {
			this.index = index;
			this.data = data;
		}
	}

}
