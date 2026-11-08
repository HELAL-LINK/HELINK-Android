package com.alihelal.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A cancellation signal shared by a piece of work and everything it starts (like Go's
 * context): cancelling it stops running programs and open connections right away.
 */
public final class Cancel {
    private final Cancel parent;
    private final Runnable parentHook;
    private boolean cancelled;
    private final List<Runnable> hooks = new ArrayList<Runnable>();

    public Cancel() {
        this(null);
    }

    /** A child is cancelled together with its parent (and can be cancelled on its own). */
    public Cancel(Cancel parent) {
        this.parent = parent;
        if (parent != null) {
            final Cancel self = this;
            parentHook = new Runnable() {
                @Override
                public void run() {
                    self.cancel();
                }
            };
            parent.onCancel(parentHook);
        } else {
            parentHook = null;
        }
    }

    public void cancel() {
        List<Runnable> run;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            run = new ArrayList<Runnable>(hooks);
            hooks.clear();
        }
        for (Runnable r : run) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // a hook must never stop the others
            }
        }
    }

    public boolean isCancelled() {
        synchronized (this) {
            if (cancelled) return true;
        }
        return parent != null && parent.isCancelled();
    }

    /** Runs r when cancelled (right away if it already is). */
    public void onCancel(Runnable r) {
        synchronized (this) {
            if (!cancelled) {
                hooks.add(r);
                return;
            }
        }
        r.run();
    }

    public synchronized void removeHook(Runnable r) {
        hooks.remove(r);
    }

    /** Stops listening to the parent: call when the work is over. */
    public void done() {
        if (parent != null) parent.removeHook(parentHook);
        synchronized (this) {
            hooks.clear();
        }
    }
}
