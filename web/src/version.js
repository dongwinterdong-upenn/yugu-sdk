// Copyright 2026 优谷雅言 open.shengzhiai.com. Licensed under the Apache License, Version 2.0.

/** SDK version. A unit test keeps it equal to package.json. */
export const SDK_VERSION = '2.0.0';

/** SDK name used in the User-Agent or X-Yugu-SDK header. */
export const SDK_NAME = 'yugu-web-sdk';

/** Default value of the userAgent option. */
export const DEFAULT_USER_AGENT = `${SDK_NAME}/${SDK_VERSION}`;

/** Default REST base. */
export const DEFAULT_BASE_URL = 'https://open.shengzhiai.com';

/** Default WebSocket base. */
export const DEFAULT_WS_BASE_URL = 'wss://open.shengzhiai.com';
