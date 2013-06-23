/**
 * Copyright (c) 2009-2013, rultor.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met: 1) Redistributions of source code must retain the above
 * copyright notice, this list of conditions and the following
 * disclaimer. 2) Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution. 3) Neither the name of the rultor.com nor
 * the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.rultor.base;

import com.google.common.collect.ImmutableMap;
import com.jcabi.aspects.Immutable;
import com.jcabi.aspects.Loggable;
import com.jcabi.aspects.Tv;
import com.jcabi.log.Logger;
import com.rultor.spi.Instance;
import com.rultor.spi.Work;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import javax.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Sends pulses through, only on certain time moments.
 *
 * @author Yegor Bugayenko (yegor@tpc2.com)
 * @version $Id$
 * @since 1.0
 * @see <a href="https://en.wikipedia.org/wiki/Cron">Cron in Wikipedia</a>
 * @see <a href="http://pubs.opengroup.org/onlinepubs/9699919799/utilities/crontab.html">Crontab specification</a>
 */
@Immutable
@EqualsAndHashCode(of = { "origin", "gates" })
@Loggable(Loggable.DEBUG)
@SuppressWarnings({ "PMD.TooManyMethods", "PMD.CyclomaticComplexity" })
public final class Crontab implements Instance {

    /**
     * Pre-defined definitions.
     * @see <a href="https://en.wikipedia.org/wiki/Cron#Predefined_scheduling_definitions">explanation</a>
     */
    private static final ImmutableMap<String, String> DEFS =
        new ImmutableMap.Builder<String, String>()
            // @checkstyle MultipleStringLiterals (1 line)
            .put("@yearly", "0 0 1 1 *")
            .put("@annually", "0 0 1 1 *")
            .put("@monthly", "0 0 1 * *")
            .put("@weekly", "0 0 * * 0")
            .put("@daily", "0 0 * * *")
            .put("@hourly", "0 * * * *")
            .build();

    /**
     * Origin.
     */
    private final transient Instance origin;

    /**
     * All gates.
     */
    private final transient Crontab.Gate<Calendar>[] gates;

    /**
     * Public ctor.
     * @param text Mask to use
     * @param instance Original instance
     */
    public Crontab(final String text, final Instance instance) {
        this.origin = instance;
        this.gates = Crontab.split(text);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Loggable(value = Loggable.DEBUG, limit = Integer.MAX_VALUE)
    public void pulse(@NotNull final Work work) throws Exception {
        final Calendar today = Crontab.today();
        boolean pass = true;
        for (Crontab.Gate<Calendar> gate : this.gates) {
            if (!gate.pass(today)) {
                pass = false;
                break;
            }
        }
        if (pass) {
            this.origin.pulse(work);
        } else {
            Logger.info(
                this,
                "Not the right moment, see you again in %[ms]s",
                this.lag()
            );
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return Logger.format("%s in %[ms]s", this.origin, this.lag());
    }

    /**
     * Lag in msec.
     * @return Msec
     */
    private long lag() {
        final Calendar today = Crontab.today();
        long lag = 0;
        for (Crontab.Gate<Calendar> gate : this.gates) {
            lag += gate.lag(today);
        }
        return lag;
    }

    /**
     * Gate condition.
     */
    @Immutable
    private interface Gate<T> {
        /**
         * Pass or not.
         * @param calendar Calendar to check
         * @return TRUE if it's a good time to go through
         */
        boolean pass(T calendar);
        /**
         * How many milliseconds to wait before the next opportunity.
         * @param calendar Calendar to check
         * @return Lag in milliseconds
         */
        long lag(T calendar);
    }

    /**
     * Abstract gate.
     */
    @ToString
    @Immutable
    @EqualsAndHashCode(of = "alternatives")
    @Loggable(Loggable.DEBUG)
    private abstract static class AbstractGate
        implements Crontab.Gate<Calendar> {
        /**
         * All alternatives.
         */
        private final transient Crontab.Gate<Integer>[] alternatives;
        /**
         * Public ctor.
         * @param text Text spec
         */
        @SuppressWarnings("unchecked")
        protected AbstractGate(final String text) {
            final String[] parts = text.split(",");
            this.alternatives =
                (Crontab.Gate<Integer>[]) new Crontab.Gate<?>[parts.length];
            for (int idx = 0; idx < parts.length; ++idx) {
                this.alternatives[idx] = Crontab.AbstractGate.parse(parts[idx]);
            }
        }
        /**
         * Matches the number?
         * @param input Input number
         * @return TRUE if matches
         */
        protected boolean matches(final int input) {
            boolean matches = false;
            for (Crontab.Gate<Integer> alternative : this.alternatives) {
                if (alternative.pass(input)) {
                    matches = true;
                    break;
                }
            }
            return matches;
        }
        /**
         * Calculate the lag.
         * @param input Input number
         * @return Lag in milliseconds
         */
        protected long lag(final int input) {
            long lag = Long.MAX_VALUE;
            for (Crontab.Gate<Integer> alternative : this.alternatives) {
                lag = Math.min(lag, alternative.lag(input));
            }
            return lag;
        }
        /**
         * Parse text into alternative.
         * @param part The text to parse
         * @return Crontab.Gate
         */
        private static Crontab.Gate<Integer> parse(final String part) {
            final Crontab.Gate<Integer> alternative;
            if (part.matches("\\d+")) {
                alternative = new Crontab.Gate<Integer>() {
                    @Override
                    public boolean pass(final Integer num) {
                        return num.equals(Integer.valueOf(part));
                    }
                    @Override
                    public long lag(final Integer num) {
                        return Math.abs(num - Integer.valueOf(part));
                    }
                };
            } else if (part.matches("\\d+-\\d+")) {
                final String[] numbers = part.split("-");
                final int left = Integer.valueOf(numbers[0]);
                final int right = Integer.valueOf(numbers[1]);
                alternative = new Crontab.Gate<Integer>() {
                    @Override
                    public boolean pass(final Integer num) {
                        return num >= left || num <= right;
                    }
                    @Override
                    public long lag(final Integer num) {
                        long lag = 0;
                        if (!this.pass(num)) {
                            lag = Math.abs(num - left);
                        }
                        return lag;
                    }
                };
            } else if (part.matches("\\*/\\d+")) {
                final String[] sectors = part.split("/");
                final int div = Integer.valueOf(sectors[1]);
                alternative = new Crontab.Gate<Integer>() {
                    @Override
                    public boolean pass(final Integer num) {
                        return num / div == 0;
                    }
                    @Override
                    public long lag(final Integer num) {
                        long lag = 0;
                        if (!this.pass(num)) {
                            lag = Math.abs(num - div);
                        }
                        return lag;
                    }
                };
            } else if ("*".equals(part)) {
                alternative = new Crontab.Gate<Integer>() {
                    @Override
                    public boolean pass(final Integer num) {
                        return true;
                    }
                    @Override
                    public long lag(final Integer num) {
                        return 0L;
                    }
                };
            } else {
                throw new IllegalArgumentException(
                    String.format("invalid crontab sector '%s'", part)
                );
            }
            return alternative;
        }
    }

    /**
     * Split into parts.
     * @param text Text to split
     * @return Five parts, numbers
     */
    @SuppressWarnings("unchecked")
    private static Crontab.Gate<Calendar>[] split(final String text) {
        String src = text;
        if (Crontab.DEFS.containsKey(src)) {
            src = Crontab.DEFS.get(src);
        }
        final String[] parts = src.split("\\s+");
        if (parts.length != Tv.FIVE) {
            throw new IllegalArgumentException(
                String.format("invalid crontab definition '%s'", text)
            );
        }
        return (Crontab.Gate<Calendar>[]) new Crontab.Gate<?>[] {
            new Crontab.AbstractGate(parts[0]) {
                @Override
                public boolean pass(final Calendar calendar) {
                    return this.matches(calendar.get(Calendar.MINUTE));
                }
                @Override
                public long lag(final Calendar calendar) {
                    return this.lag(
                        calendar.get(Calendar.MINUTE)
                    ) * TimeUnit.MINUTES.toMillis(1);
                }
            },
            new Crontab.AbstractGate(parts[1]) {
                @Override
                public boolean pass(final Calendar calendar) {
                    return this.matches(calendar.get(Calendar.HOUR_OF_DAY));
                }
                @Override
                public long lag(final Calendar calendar) {
                    return this.lag(
                        calendar.get(Calendar.HOUR_OF_DAY)
                    ) * TimeUnit.HOURS.toMillis(1);
                }
            },
            new Crontab.AbstractGate(parts[2]) {
                @Override
                public boolean pass(final Calendar calendar) {
                    return this.matches(
                        calendar.get(Calendar.DAY_OF_MONTH) + 1
                    );
                }
                @Override
                public long lag(final Calendar calendar) {
                    return this.lag(
                        calendar.get(Calendar.DAY_OF_MONTH) + 1
                    ) * TimeUnit.DAYS.toMillis(1);
                }
            },
            new Crontab.AbstractGate(parts[Tv.THREE]) {
                @Override
                public boolean pass(final Calendar calendar) {
                    return this.matches(calendar.get(Calendar.MONTH) + 1);
                }
                @Override
                public long lag(final Calendar calendar) {
                    return this.lag(
                        calendar.get(Calendar.MONTH) + 1
                    ) * Tv.THIRTY * TimeUnit.DAYS.toMillis(1);
                }
            },
            new Crontab.AbstractGate(parts[Tv.FOUR]) {
                @Override
                public boolean pass(final Calendar calendar) {
                    return this.matches(calendar.get(Calendar.DAY_OF_WEEK));
                }
                @Override
                public long lag(final Calendar calendar) {
                    return this.lag(
                        calendar.get(Calendar.DAY_OF_WEEK)
                    ) * TimeUnit.DAYS.toMillis(1);
                }
            },
        };
    }

    /**
     * Today moment.
     * @return Calendar or today
     */
    private static Calendar today() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

}
