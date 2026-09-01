"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.taskLockManager = exports.TaskLockManager = void 0;
/** Cancels stale planner requests when a newer update arrives for the same task. */
class TaskLockManager {
    activeTasks = new Map();
    acquireLock(taskId) {
        this.activeTasks.get(taskId)?.abort();
        const controller = new AbortController();
        this.activeTasks.set(taskId, controller);
        return controller;
    }
    releaseLock(taskId, controller) {
        // Do not let an older aborted request release the controller acquired by a
        // newer request for the same task.
        if (!controller || this.activeTasks.get(taskId) === controller) {
            this.activeTasks.delete(taskId);
        }
    }
}
exports.TaskLockManager = TaskLockManager;
exports.taskLockManager = new TaskLockManager();
