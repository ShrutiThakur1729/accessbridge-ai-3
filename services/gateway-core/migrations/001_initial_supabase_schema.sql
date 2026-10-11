-- AccessBridge AI: Initial Supabase Schema & Row Level Security (RLS)
-- Idempotent Migration Script

-- 1. PROFILES
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    full_name TEXT,
    email TEXT,
    phone TEXT,
    address TEXT,
    guardian_name TEXT,
    guardian_email TEXT,
    guardian_phone TEXT,
    notification_prefs TEXT[] DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'profiles' AND policyname = 'Users can view own profile') THEN
        CREATE POLICY "Users can view own profile" ON public.profiles FOR SELECT USING (auth.uid() = id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'profiles' AND policyname = 'Users can insert own profile') THEN
        CREATE POLICY "Users can insert own profile" ON public.profiles FOR INSERT WITH CHECK (auth.uid() = id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'profiles' AND policyname = 'Users can update own profile') THEN
        CREATE POLICY "Users can update own profile" ON public.profiles FOR UPDATE USING (auth.uid() = id);
    END IF;
END $$;


-- 2. USER PREFERENCES
CREATE TABLE IF NOT EXISTS public.user_preferences (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    preferred_response_language TEXT DEFAULT 'en',
    accessibility_enabled BOOLEAN DEFAULT FALSE,
    battery_optimization_ignored BOOLEAN DEFAULT FALSE,
    memory_consent BOOLEAN DEFAULT FALSE,
    telemetry_consent BOOLEAN DEFAULT TRUE,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.user_preferences ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'user_preferences' AND policyname = 'Users can manage own preferences') THEN
        CREATE POLICY "Users can manage own preferences" ON public.user_preferences FOR ALL USING (auth.uid() = user_id);
    END IF;
END $$;


-- 3. USER MEMORIES
CREATE TABLE IF NOT EXISTS public.user_memories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    fact_key TEXT NOT NULL,
    fact_value TEXT NOT NULL,
    source TEXT DEFAULT 'user_approved',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_memories_user_id ON public.user_memories(user_id);

ALTER TABLE public.user_memories ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'user_memories' AND policyname = 'Users can manage own memories') THEN
        CREATE POLICY "Users can manage own memories" ON public.user_memories FOR ALL USING (auth.uid() = user_id);
    END IF;
END $$;


-- 4. TASKS
CREATE TABLE IF NOT EXISTS public.tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    user_goal TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'IN_PROGRESS',
    risk_level TEXT DEFAULT 'GREEN',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tasks_user_id ON public.tasks(user_id);

ALTER TABLE public.tasks ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'tasks' AND policyname = 'Users can manage own tasks') THEN
        CREATE POLICY "Users can manage own tasks" ON public.tasks FOR ALL USING (auth.uid() = user_id);
    END IF;
END $$;


-- 5. TASK STEPS
CREATE TABLE IF NOT EXISTS public.task_steps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES public.tasks(id) ON DELETE CASCADE,
    step_index INTEGER NOT NULL,
    action_type TEXT NOT NULL,
    execution_state TEXT NOT NULL DEFAULT 'PENDING',
    error_code TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_task_steps_task_id ON public.task_steps(task_id);

ALTER TABLE public.task_steps ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'task_steps' AND policyname = 'Users can manage steps of own tasks') THEN
        CREATE POLICY "Users can manage steps of own tasks" ON public.task_steps FOR ALL USING (
            EXISTS (SELECT 1 FROM public.tasks WHERE public.tasks.id = public.task_steps.task_id AND public.tasks.user_id = auth.uid())
        );
    END IF;
END $$;


-- 6. TASK FEEDBACK
CREATE TABLE IF NOT EXISTS public.task_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES public.tasks(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    rating INTEGER CHECK (rating >= 1 AND rating <= 5),
    feedback_text TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE public.task_feedback ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'task_feedback' AND policyname = 'Users can manage own feedback') THEN
        CREATE POLICY "Users can manage own feedback" ON public.task_feedback FOR ALL USING (auth.uid() = user_id);
    END IF;
END $$;


-- 7. ANONYMOUS TELEMETRY EVENTS (NO USER PII, INSERT ONLY FOR CLIENTS)
CREATE TABLE IF NOT EXISTS public.telemetry_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    anonymous_event_id UUID NOT NULL DEFAULT gen_random_uuid(),
    action_category TEXT NOT NULL,
    failure_code TEXT NOT NULL,
    target_app_package TEXT,
    coarse_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_telemetry_pkg ON public.telemetry_events(target_app_package);

ALTER TABLE public.telemetry_events ENABLE ROW LEVEL SECURITY;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename = 'telemetry_events' AND policyname = 'Anyone can submit telemetry') THEN
        CREATE POLICY "Anyone can submit telemetry" ON public.telemetry_events FOR INSERT WITH CHECK (true);
    END IF;
END $$;
