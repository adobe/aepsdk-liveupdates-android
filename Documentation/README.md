# AEPLiveUpdates Documentation

### Installation

- [Getting started](./sources/getting-started.md)
- [Live Updates is a plugin, not an extension](./sources/getting-started.md#a-plugin-not-an-extension)
- [Test app setup](./sources/testapp-setup.md)

### Live Updates

- Prerequisites
  - Enable push notifications in your app by [adding the Firebase dependency.](https://firebase.google.com/docs/cloud-messaging/android/client)
  - Set up push messaging with the [Messaging extension](https://github.com/adobe/aepsdk-messaging-android/blob/main/Documentation/README.md).
- Developer Documentation
  - [API usage](./sources/api-usage.md)
  - [Live Update payload](./sources/payload.md)
- Guides
  - [Automatic, mixed, and manual handling of Live Updates](./sources/integration-patterns.md)
  - [Start a Live Update locally](./sources/integration-patterns.md#local-live-updates)
  - [Subscribe to FCM topics for broadcast Live Updates](./sources/integration-patterns.md#topic-subscriptions)
  - [Handle Live Update taps and dismissals](./sources/integration-patterns.md#handling-taps-and-dismissals)
  - [Tracking and diagnostic events](./sources/tracking-and-diagnostics.md)

### Design

- [Architecture](./architecture.md)
