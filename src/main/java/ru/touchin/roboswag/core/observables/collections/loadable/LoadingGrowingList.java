/*
 *  Copyright (c) 2015 RoboSwag (Gavriil Sitnikov, Vsevolod Ivanov)
 *
 *  This file is part of RoboSwag library.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package ru.touchin.roboswag.core.observables.collections.loadable;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.NoSuchElementException;

import ru.touchin.roboswag.core.log.Lc;
import ru.touchin.roboswag.core.observables.collections.ObservableCollection;
import ru.touchin.roboswag.core.observables.collections.ObservableList;
import ru.touchin.roboswag.core.utils.android.RxAndroidUtils;
import rx.Observable;
import rx.Scheduler;
import rx.schedulers.Schedulers;
import rx.subjects.BehaviorSubject;

/**
 * Created by Gavriil Sitnikov on 23/05/16.
 * TODO: description
 */
public class LoadingGrowingList<TItemId, TItem extends ItemWithId<TItemId>>
        extends ObservableCollection<TItem> {

    @NonNull
    private final Scheduler scheduler = RxAndroidUtils.createLooperScheduler();
    @NonNull
    private final LoadingRequestCreator<TItem, TItemId> loadingMoreRequestCreator;
    @Nullable
    private Observable<?> loadingMoreConcreteObservable;
    @NonNull
    private final BehaviorSubject<Boolean> haveMoreItems = BehaviorSubject.create(true);
    @NonNull
    private final ObservableList<TItem> innerList = new ObservableList<>();

    public LoadingGrowingList(@NonNull final LoadingRequestCreator<TItem, TItemId> loadingMoreRequestCreator) {
        super();
        this.loadingMoreRequestCreator = loadingMoreRequestCreator;
        innerList.observeChanges().subscribe(change -> {
            //do not change - bug of RetroLambda
            notifyAboutChange(change);
        });
    }

    @NonNull
    protected ObservableList<TItem> getInnerList() {
        return innerList;
    }

    @NonNull
    public Observable<Boolean> observeHaveMoreItems() {
        return haveMoreItems.distinctUntilChanged();
    }

    @NonNull
    private Observable<?> getLoadMoreObservable() {
        return Observable
                .switchOnNext(Observable.<Observable<?>>create(subscriber -> {
                    if (loadingMoreConcreteObservable == null) {
                        final TItemId fromItemId = !innerList.isEmpty() ? get(size() - 1).getItemId() : null;
                        loadingMoreConcreteObservable = loadingMoreRequestCreator
                                .loadByItemId(new LoadingFromRequest<>(fromItemId, size() - 1))
                                .subscribeOn(Schedulers.io())
                                .observeOn(scheduler)
                                .single()
                                .doOnError(throwable -> {
                                    if ((throwable instanceof IllegalArgumentException)
                                            || (throwable instanceof NoSuchElementException)) {
                                        Lc.assertion("Updates during loading not supported. LoadingRequestCreator should emit only one result.");
                                    }
                                })
                                .doOnNext(loadedItems -> {
                                    loadingMoreConcreteObservable = null;
                                    innerList.addAll(loadedItems.getItems());
                                    haveMoreItems.onNext(loadedItems.haveMoreItems());
                                })
                                .replay(1)
                                .refCount();
                    }
                    subscriber.onNext(loadingMoreConcreteObservable);
                    subscriber.onCompleted();
                }))
                .subscribeOn(scheduler);
    }

    @Override
    public int size() {
        return innerList.size();
    }

    @NonNull
    @Override
    public TItem get(final int position) {
        return innerList.get(position);
    }

    @NonNull
    @Override
    public Observable<TItem> loadItem(final int position) {
        return Observable
                .switchOnNext(Observable
                        .<Observable<TItem>>create(subscriber -> {
                            if (position < size()) {
                                subscriber.onNext(Observable.just(get(position)));
                            } else if (!haveMoreItems.getValue()) {
                                subscriber.onNext(Observable.just((TItem) null));
                            } else {
                                subscriber.onNext(getLoadMoreObservable().switchMap(ignored -> Observable.<TItem>error(new DoRetryException())));
                            }
                            subscriber.onCompleted();
                        })
                        .subscribeOn(scheduler))
                .retryWhen(attempts -> attempts
                        .switchMap(throwable -> throwable instanceof DoRetryException ? Observable.just(null) : Observable.error(throwable)));
    }

    private static class DoRetryException extends Exception {
    }

}