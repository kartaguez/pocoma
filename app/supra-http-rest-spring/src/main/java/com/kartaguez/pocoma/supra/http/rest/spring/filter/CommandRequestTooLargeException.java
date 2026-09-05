package com.kartaguez.pocoma.supra.http.rest.spring.filter;

import java.io.IOException;

/** Raised while streaming a Command request whose Content-Length was absent or inaccurate. */
public final class CommandRequestTooLargeException extends IOException {
}
