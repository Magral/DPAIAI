package io.github.dovecoteescapee.byedpi

import io.github.dovecoteescapee.byedpi.routing.ServiceRouter

/** Host integration entry point; uses the exact production router, no Android mocks. */
object RouterHarness {
    @JvmStatic fun main(args: Array<String>) {
        ServiceRouter(0).use { router ->
            var generation = 1L
            router.route("instagram",ServiceRouter.Route(args[0].toInt(),generation++))
            router.route("youtube",ServiceRouter.Route(args[1].toInt(),generation++))
            router.start();println("READY ${router.port}");System.out.flush()
            generateSequence(::readLine).forEach { line ->
                if (line == "stats") println("STATS ${router.stats("instagram").rx} ${router.stats("youtube").rx}")
                else {
                    val fields = line.split(' ');router.route(fields[0],ServiceRouter.Route(fields[1].toInt(),generation++));println("SWAPPED")
                }
                System.out.flush()
            }
        }
    }
}
