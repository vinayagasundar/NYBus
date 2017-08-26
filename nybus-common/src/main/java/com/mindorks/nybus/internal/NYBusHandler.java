/*
 *    Copyright (C) 2017 MINDORKS NEXTGEN PRIVATE LIMITED
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.mindorks.nybus.internal;

import com.mindorks.nybus.annotation.Subscribe;
import com.mindorks.nybus.event.Event;
import com.mindorks.nybus.scheduler.SchedulerProvider;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.BackpressureStrategy;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Consumer;
import io.reactivex.subjects.PublishSubject;


/**
 * Created by Jyoti on 14/08/17.
 */

public class NYBusHandler {
    private SchedulerProvider mSchedulerProvider;
    private ConcurrentHashMap<Class<?>, ConcurrentHashMap<Object, Set<Method>>  > mEventsToTargetsMap;
    private PublishSubject<Event> subject;


    public NYBusHandler() {
        mEventsToTargetsMap = new ConcurrentHashMap<>();
        subject = PublishSubject.create();
        subject.toFlowable(BackpressureStrategy.BUFFER)
                .subscribe(new Consumer<Event>() {
                    @Override
                    public void accept(@NonNull Event event) throws Exception {
                        ConcurrentHashMap<Object, Set<Method>> mTargetMap = mEventsToTargetsMap.
                                get(event.object.getClass());
                        if (mTargetMap != null) {
                            for (Map.Entry<Object, Set<Method>> mTargetMapEntry :
                                    mTargetMap.entrySet()) {
                                Set<Method> mSubscribedMethods = mTargetMapEntry.getValue();
                                for (Method subscribedMethod : mSubscribedMethods) {
                                    String methodChannelId = getMethodChannelId(subscribedMethod);
                                    if (methodChannelId.equals(event.channelId)) {
                                        deliverEventToTargetMethod(mTargetMapEntry.getKey(),
                                                subscribedMethod, event.object);
                                    }

                                }
                            }
                        }


                    }


                });

    }

    public void setSchedulerProvider(SchedulerProvider mSchedulerProvider) {
        this.mSchedulerProvider = mSchedulerProvider;
    }

    public void register(Object object, List<String> channelId) {
        List<Method> subscribeMethods =
                provideMethodsWithSubscribeAnnotation(object.getClass());
        if (subscribeMethods.size() != 0) {
            for (Method subscribedMethod : subscribeMethods) {
                addEntriesInTargetMap(object, subscribedMethod, channelId);
            }

        }
    }

    public void post(Object eventObject, String channelId) {
        subject.onNext(new Event(eventObject, channelId));
    }


    public void unregister(Object targetObject, List<String> targetChannelId) {
        for (Map.Entry<Class<?>, ConcurrentHashMap<Object, Set<Method>>> mEventsToTargetsMapEntry :
                mEventsToTargetsMap.entrySet()) {
            ConcurrentHashMap<Object, Set<Method>> mTargetMap = mEventsToTargetsMapEntry.getValue();
            if (mTargetMap != null) {
                for (Map.Entry<Object, Set<Method>> mTargetMapEntry : mTargetMap.entrySet()) {
                    if (mTargetMapEntry.getKey().equals(targetObject)) {
                        removeMethodFromCurrentMethodSet(mTargetMap, targetObject, targetChannelId);
                        removeEventIfRequired(mTargetMap, mEventsToTargetsMapEntry);

                    }
                }

            }


        }
    }


    private List<Method> provideMethodsWithSubscribeAnnotation(Class<?> subscriber) {
        List<Method> subscribeAnnotatedMethods = new ArrayList<>();
        Method[] declaredMethods = subscriber.getDeclaredMethods();
        for (Method method : declaredMethods) {
            boolean isMethodValid = hasSubscribeAnnotation(method) && isAccessModifierPublic(method)
                    && isReturnTypeVoid(method) && hasSingleParameter(method);
            if (isMethodValid) {
                subscribeAnnotatedMethods.add(method);
            }
        }
        return subscribeAnnotatedMethods;

    }

    private void deliverEventToTargetMethod(Object targetObject, Method subscribeMethod,
                                            Object eventObject) {
        try {
            Method method = subscribeMethod;
            method.setAccessible(true);
            method.invoke(targetObject, eventObject);

        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }

    }


    private void addEntriesInTargetMap(Object targetObject,
                                       Method subscribeMethod, List<String> targetChannelId) {
        String subscribedMethodChannelId = getMethodChannelId(subscribeMethod);
        if (targetChannelId.contains(subscribedMethodChannelId)) {
            if (mEventsToTargetsMap.containsKey(subscribeMethod.getParameterTypes()[0])) {
                addOrUpdateMethodsInTargetMap(targetObject, subscribeMethod);
            } else {
                createNewEventInEventsToTargetsMap(targetObject, subscribeMethod);
            }

        }
    }

    private String getMethodChannelId(Method subscribeMethod) {
        Subscribe subscribeAnnotation = subscribeMethod.getAnnotation(Subscribe.class);
        return subscribeAnnotation.channelId();
    }

    private void createNewEventInEventsToTargetsMap(Object targetObject,
                                                    Method subscribeMethod) {
        ConcurrentHashMap<Object, Set<Method>> valuesForEventsToTargetsMap =
                new ConcurrentHashMap<>();
        Set<Method> methodSet = new HashSet<>();
        methodSet.add(subscribeMethod);
        valuesForEventsToTargetsMap.put(targetObject, methodSet);
        mEventsToTargetsMap.put(subscribeMethod.getParameterTypes()[0],
                valuesForEventsToTargetsMap);
    }

    private void addOrUpdateMethodsInTargetMap(Object targetObject,
                                               Method subscribeMethod) {
        ConcurrentHashMap<Object, Set<Method>> mTargetMap =
                mEventsToTargetsMap.get(subscribeMethod.getParameterTypes()[0]);
        if (mTargetMap.containsKey(targetObject)) {
            updateMethodInSet(targetObject, subscribeMethod, mTargetMap);
        } else {
            addEntryInTargetMap(targetObject, subscribeMethod, mTargetMap);
        }

    }

    private void updateMethodInSet(Object targetObject,
                                   Method subscribeMethod,
                                   ConcurrentHashMap<Object, Set<Method>> mTargetMap) {
        Set<Method> methodSet = mTargetMap.get(targetObject);
        methodSet.add(subscribeMethod);
    }

    private void addEntryInTargetMap(Object targetObject,
                                     Method subscribeMethod,
                                     ConcurrentHashMap<Object, Set<Method>> mTargetMap) {
        Set<Method> methodSet = new HashSet<>();
        methodSet.add(subscribeMethod);
        mTargetMap.put(targetObject, methodSet);
    }

    private void removeMethodFromCurrentMethodSet(ConcurrentHashMap<Object, Set<Method>> mTargetMap,
                                                  Object targetObject,
                                                  List<String> targetChannelId) {

        Set<Method> subscribedMethods = mTargetMap.get(targetObject);
        Iterator subscribedMethodsIterator = subscribedMethods.iterator();
        while (subscribedMethodsIterator.hasNext()) {
            Method method = (Method)subscribedMethodsIterator.next();
            String methodChannelId = getMethodChannelId(method);
            if (targetChannelId.contains(methodChannelId)) {
                subscribedMethodsIterator.remove();
                removeTargetIfRequired(subscribedMethods, mTargetMap, targetObject);
            }
        }

    }

    private void removeTargetIfRequired(Set<Method> subscribedMethods, ConcurrentHashMap<Object,
            Set<Method>> mTargetMap, Object targetObject) {
        if (subscribedMethods.size() == 0) {
            mTargetMap.remove(targetObject);
        }
    }

    private void removeEventIfRequired(ConcurrentHashMap<Object, Set<Method>> mTargetMap,
                                       Map.Entry<Class<?>, ConcurrentHashMap<Object, Set<Method>>>
                                               mEventsToTargetsMapEntry) {
        if (mTargetMap.size() == 0) {
            mEventsToTargetsMap.remove(mEventsToTargetsMapEntry.getKey());
        }
    }

    private boolean hasSubscribeAnnotation(Method method) {
        Subscribe subscribeAnnotation = method.getAnnotation(Subscribe.class);
        return subscribeAnnotation != null;

    }

    private boolean isAccessModifierPublic(Method method) {
        return (method.getModifiers() & Modifier.PUBLIC) != 0;
    }

    private boolean isReturnTypeVoid(Method method) {
        return (method.getReturnType().equals(Void.TYPE));
    }

    private boolean hasSingleParameter(Method method) {
        return method.getParameterCount() == 1;
    }
}

