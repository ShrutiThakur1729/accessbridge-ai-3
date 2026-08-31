/** Cancels stale planner requests when a newer update arrives for the same task. */
export class TaskLockManager {
  private activeTasks = new Map<string, AbortController>();

  acquireLock(taskId: string): AbortController {
    this.activeTasks.get(taskId)?.abort();
    const controller = new AbortController();
    this.activeTasks.set(taskId, controller);
    return controller;
  }

  releaseLock(taskId: string, controller?: AbortController): void {
    // Do not let an older aborted request release the controller acquired by a
    // newer request for the same task.
    if (!controller || this.activeTasks.get(taskId) === controller) {
      this.activeTasks.delete(taskId);
    }
  }
}

export const taskLockManager = new TaskLockManager();
