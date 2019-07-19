[![Build Status](https://travis-ci.org/natario1/Transcoder.svg?branch=master)](https://travis-ci.org/natario1/Transcoder)
[![Release](https://img.shields.io/github/release/natario1/Transcoder.svg)](https://github.com/natario1/Transcoder/releases)
[![Issues](https://img.shields.io/github/issues-raw/natario1/Transcoder.svg)](https://github.com/natario1/Transcoder/issues)

# Transcoder

Transcodes and compresses video files into the MP4 format, with audio support, using hardware accelerated Android codecs available on the device. Works on API 18+.

```groovy
implementation 'com.otaliastudios:transcoder:0.4.0'
```

Using Transcoder in the most basic form is pretty simple:

```java
MediaTranscoder.into(filePath)
        .setDataSource(context, uri) // or...
        .setDataSource(filePath) // or...
        .setDataSource(fileDescriptor) // or...
        .setDataSource(dataSource)
        .setListener(new MediaTranscoder.Listener() {
             public void onTranscodeProgress(double progress) {}
             public void onTranscodeCompleted(int successCode) {}
             public void onTranscodeCanceled() {}
             public void onTranscodeFailed(@NonNull Throwable exception) {}
        }).transcode()
```

Take a look at the demo app for a real example or keep reading below for documentation.

*Note: this project is an improved fork of [ypresto/android-transcoder](https://github.com/ypresto/android-transcoder).
It features a lot of improvements over the original project, including:*

- *Multithreading support*
- *Various bugs fixed*
- *[Input](#data-sources): Accept content Uris and other types*
- *[Real error handling](#listening-for-events) instead of errors being thrown*
- *Frame dropping support, which means you can set the video frame rate*
- *Source project is over-conservative when choosing options that *might* not be supported. We prefer to try and let the codec fail*
- *More convenient APIs for transcoding & choosing options*
- *Configurable [Validators](#validators) to e.g. **not** perform transcoding if the source video is already compressed enough*
- *Expose internal logs through Logger (so they can be reported to e.g. Crashlytics)*
- *Handy utilities for track configuration through [Output Strategies](#output-strategies)*
- *Handy utilities for resizing*

## Setup

This library requires API level 18 (Android 4.3, JELLY_BEAN_MR2) or later.
If your app targets older versions, you can override the minSdkVersion by
adding this line to your manifest file:

```xml
<uses-sdk tools:overrideLibrary="com.otaliastudios.transcoder" />
```

In this case you should check at runtime that API level is at least 18, before
calling any method here.

## Data Sources

Starting a transcoding operation will require a source for our data, which is not necessarily
a `File`. The `DataSource` objects will automatically take care about releasing streams / resources,
which is convenient but it means that they can not be used twice.

#### `UriDataSource`

The Android friendly source can be created with `new UriDataSource(context, uri)` or simply
using `setDataSource(context, uri)` in the transcoding builder.

#### `FileDescriptorDataSource`

A data source backed by a file descriptor. Use `new FileDescriptorDataSource(descriptor)` or
simply `setDataSource(descriptor)` in the transcoding builder.

#### `FilePathDataSource`

A data source backed by a file absolute path. Use `new FilePathDataSource(path)` or
simply `setDataSource(path)` in the transcoding builder.

## Listening for events

Transcoding will happen on a background thread, but we will send updates through the `MediaTranscoder.Listener`
interface, which can be applied when building the request:

```java
MediaTranscoder.into(filePath)
        .setListenerHandler(handler)
        .setListener(new MediaTranscoder.Listener() {
             public void onTranscodeProgress(double progress) {}
             public void onTranscodeCompleted(int successCode) {}
             public void onTranscodeCanceled() {}
             public void onTranscodeFailed(@NonNull Throwable exception) {}
        })
        // ...
```

All of the listener callbacks are called:

- If present, on the handler specified by `setListenerHandler()`
- If it has a handler, on the thread that started the `transcode()` call
- As a last resort, on the UI thread

#### `onTranscodeProgress`

This simply sends a double indicating the current progress. The value is typically between 0 and 1,
but can be a negative value to indicate that we are not able to compute progress (yet?).

This is the right place to update a ProgressBar, for example.

#### `onTranscodeCanceled`

The transcoding operation was canceled. This can happen when the `Future` returned by `transcode()`
is cancelled by the user.

#### `onTranscodeFailed`

This can happen in a number of cases and is typically out of our control. Input options might be
wrong, write permissions might be missing, codec might be absent, input file might be not supported
or simply corrupted.

You can take a look at the `Throwable` being passed to know more about the exception.

#### `onTranscodeCompleted`

Transcoding operation did succeed. The success code can be:

|Code|Meaning|
|----|-------|
|`MediaTranscoder.SUCCESS_TRANSCODED`|Transcoding was executed successfully. Transcoded file was written to the output path.|
|`MediaTranscoder.SUCCESS_NOT_NEEDED`|Transcoding was not executed because it was considered **not needed** by the `Validator`.|

Keep reading [below](#validators) to know about `Validator`s.

## Validators

Validators tell the engine whether the transcoding process should start or not based on the status
of the audio and video track.

```java
MediaTranscoder.into(filePath)
        .setValidator(validator)
        // ...
```

This can be used, for example, to:

- avoid transcoding when video resolution is already OK with our needs
- avoid operating on files without an audio/video stream
- avoid operating on files with an audio/video stream

Validators should implement the `validate(TrackStatus, TrackStatus)` and inspect the status for video
and audio tracks. When `false` is returned, transcoding will complete with the `SUCCESS_NOT_NEEDED` status code.
The TrackStatus enum contains the following values:

|Value|Meaning|
|-----|-------|
|`TrackStatus.ABSENT`|This track was absent in the source file.|
|`TrackStatus.PASS_THROUGH`|This track is about to be copied as-is in the target file.|
|`TrackStatus.COMPRESSING`|This track is about to be processed and compressed in the target file.|
|`TrackStatus.REMOVING`|This track will be removed in the target file.|

The `TrackStatus` value depends on the [output strategy](#output-strategies) that was used.
We provide a few validators that can be injected for typical usage.

#### `DefaultValidator`

This is the default validator and it returns true when any of the track is `COMPRESSING` or `REMOVING`.
In the other cases, transcoding is typically not needed so we abort the operation.

#### `WriteAlwaysValidator`

This validator always returns true and as such will always write to target file, no matter the track status,
presence of tracks and so on. For instance, the output container file might have no tracks.

#### `WriteVideoValidator`

A Validator that gives priority to the video track. Transcoding will not happen if the video track does not need it,
even if the audio track might need it. If reducing file size is your only concern, this can avoid compressing
files that would not benefit so much from compressing the audio track only.

## Output Strategies

Output strategies return options for each track (audio or video) for the engine to understand **how**
and **if** this track should be transcoded, and whether the whole process should be aborted.

```java
MediaTranscoder.into(filePath)
        .setVideoOutputStrategy(videoStrategy)
        .setAudioOutputStrategy(audioStrategy)
        // ...
```

The point of `OutputStrategy` is to inspect the input `android.media.MediaFormat` and return
the output `android.media.MediaFormat`, filled with required options.

This library offers track specific strategies that help with audio and video options (see
[Audio Strategies](#audio-strategies) and [Video Strategies](#video-strategies)).
In addition, we have a few built-in strategies that can work for both audio and video:

#### `PassThroughTrackStrategy`

An OutputStrategy that asks the encoder to keep this track as is, by returning the same input
format. Note that this is risky, as the input track format might not be supported my the MP4 container.

This will set the `TrackStatus` to `TrackStatus.PASS_THROUGH`.

#### `RemoveTrackStrategy`

An OutputStrategy that asks the encoder to remove this track from the output container, by returning null.
For instance, this can be used as an audio strategy to remove audio from video/audio streams.

This will set the `TrackStatus` to `TrackStatus.REMOVING`.

## Audio Strategies

The default internal strategy for audio is a `DefaultAudioStrategy`, which converts the
audio stream to AAC format with the specified number of channels.

```java
MediaTranscoder.into(filePath)
        .setAudioOutputStrategy(DefaultAudioStrategy(1)) // or..
        .setAudioOutputStrategy(DefaultAudioStrategy(2)) // or..
        .setAudioOutputStrategy(DefaultAudioStrategy(DefaultAudioStrategy.AUDIO_CHANNELS_AS_IS))
        // ...
```

Take a look at the source code to understand how to manage the `android.media.MediaFormat` object.

## Video Strategies

The default internal strategy for video is a `DefaultVideoStrategy`, which converts the
video stream to AVC format and is very configurable. The class helps in defining an output size
that matches the aspect ratio of the input stream size, which is a basic requirement for video strategies.

### Video Size

We provide helpers for common tasks:

```java
DefaultVideoStrategy strategy;

// Sets an exact size. Of course this is risky if you don't read the input size first.
strategy = DefaultVideoStrategy.exact(1080, 720).build()

// Keeps the aspect ratio, but scales down the input size with the given fraction.
strategy = DefaultVideoStrategy.fraction(0.5F).build()

// Ensures that each video size is at most the given value - scales down otherwise.
strategy = DefaultVideoStrategy.atMost(1000).build()

// Ensures that minor and major dimension are at most the given values - scales down otherwise.
strategy = DefaultVideoStrategy.atMost(500, 1000).build()
```

In fact, all of these will simply call `new DefaultVideoStrategy.Builder(resizer)` with a special
resizer. We offer handy resizers:

|Name|Description|
|----|-----------|
|`ExactResizer`|Returns the dimensions passed to the constructor. Throws if aspect ratio does not match.|
|`FractionResizer`|Reduces the input size by the given fraction (0..1).|
|`AtMostResizer`|If needed, reduces the input size so that the "at most" constraints are matched. Aspect ratio is kept.|
|`PassThroughResizer`|Returns the input size unchanged.|

You can also group resizers through `MultiResizer`, which applies resizers in chain:

```java
// First scales down, then ensures size is at most 1000. Order matters!
Resizer resizer = new MultiResizer()
resizer.addResizer(new FractionResizer(0.5F))
resizer.addResizer(new AtMostResizer(1000))
```

This option is already available through the DefaultVideoStrategy builder, so you can do:

```java
DefaultVideoStrategy strategy = new DefaultVideoStrategy.Builder()
        .addResizer(new FractionResizer(0.5F))
        .addResizer(new AtMostResizer(1000))
        .build()
```

### Other options

You can configure the `DefaultVideoStrategy` with other options unrelated to the video size:

```java
DefaultVideoStrategy strategy = new DefaultVideoStrategy.Builder()
        .bitRate(bitRate)
        .bitRate(DefaultVideoStrategy.BITRATE_UNKNOWN) // tries to estimate
        .frameRate(frameRate) // will be capped to the input frameRate
        .iFrameInterval(interval) // interval between I-frames in seconds
        .build()
```

## Compatibility

As stated pretty much everywhere, **not all codecs/devices/manufacturers support all sizes/options**.
This is a complex issue which is especially important for video strategies, as a wrong size can lead
to a transcoding error or corrupted file.

Android platform specifies requirements for manufacturers through the [CTS (Compatibility test suite)](https://source.android.com/compatibility/cts).
Only a few codecs and sizes are strictly required to work.

We collect common presets in the `DefaultVideoStrategies` class:

```java
MediaTranscoder.into(filePath)
        .setVideoOutputStrategy(DefaultVideoStrategies.for720x1280()) // 16:9
        .setVideoOutputStrategy(DefaultVideoStrategies.for360x480()) // 4:3
        // ...
```

## License

This project is licensed under Apache 2.0. It consists of improvements over
the [ypresto/android-transcoder](https://github.com/ypresto/android-transcoder)
project which was licensed under Apache 2.0 as well:

```
Copyright (C) 2014-2016 Yuya Tanaka

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
