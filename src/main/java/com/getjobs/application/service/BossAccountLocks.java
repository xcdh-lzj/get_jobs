package com.getjobs.application.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 投递、跟进与账号资料修改共用账号占用，避免同时操作 Boss 浏览器。 */
@Component
public class BossAccountLocks {
    private final Set<Long> reserved = new HashSet<>();
    private final java.util.concurrent.locks.ReentrantReadWriteLock edits = new java.util.concurrent.locks.ReentrantReadWriteLock();
    public void reserve(List<Long> ids) {
        edits.writeLock().lock();
        try {
            synchronized (this) {
                if (!reserved.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Boss 浏览器正被投递或跟进任务使用，请等待任务结束");
                reserved.addAll(ids);
            }
        } finally { edits.writeLock().unlock(); }
    }
    public void beginEdit(long id) {
        edits.readLock().lock();
        try { requireEditable(id); } catch (RuntimeException e) { edits.readLock().unlock(); throw e; }
    }
    public void endEdit() { edits.readLock().unlock(); }
    public void beginConfigSync(long target) {
        edits.writeLock().lock();
        try {
            requireEditable(target);
        } catch (RuntimeException e) {
            edits.writeLock().unlock();
            throw e;
        }
    }
    public void endConfigSync() { edits.writeLock().unlock(); }
    public synchronized void release() { reserved.clear(); }
    public synchronized boolean isReserved() { return !reserved.isEmpty(); }
    public synchronized boolean busy(long id) { return reserved.contains(id); }
    public synchronized void requireEditable(long id) {
        if (busy(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "该账号有投递或跟进任务，请等待任务结束后修改");
    }
}
