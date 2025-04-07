package ru.nishiol.kotbox

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

class KotboxWorkerThreadFactory : ThreadFactory {
    private val count = AtomicLong(0)

    override fun newThread(r: Runnable): Thread = Thread(r, "kotbox-worker-${count.incrementAndGet()}")
}