// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.util.Preconditions;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

/**
 * File system watcher for local filesystems. It's able to provide a list of changed files between
 * two consecutive calls. Uses the standard Java WatchService, which uses 'inotify' on Linux.
 */
public final class WatchServiceDiffAwareness extends LocalDiffAwareness {

  /**
   * Bijection from WatchKey to the (absolute) Path being watched. WatchKeys don't have this
   * functionality built-in so we do it ourselves.
   */
  private final HashBiMap<WatchKey, Path> watchKeyToDirBiMap = HashBiMap.create();

  /** Every directory is registered under this watch service. */
  private WatchService watchService;

  WatchServiceDiffAwareness(String watchRoot, WatchService watchService) {
    super(watchRoot);
    this.watchService = watchService;
  }

  @Override
  public View getCurrentView() throws BrokenDiffAwarenessException {
    Set<Path> modifiedAbsolutePaths;
    if (isFirstCall()) {
      try {
        registerSubDirectoriesAndReturnContents(watchRootPath);
      } catch (IOException e) {
        close();
        throw new BrokenDiffAwarenessException(
            "Error encountered with local file system watcher " + e);
      }
      modifiedAbsolutePaths = ImmutableSet.of();
    } else {
      try {
        modifiedAbsolutePaths = collectChanges();
      } catch (BrokenDiffAwarenessException e) {
        close();
        throw e;
      } catch (IOException e) {
        close();
        throw new BrokenDiffAwarenessException(
            "Error encountered with local file system watcher " + e);
      } catch (ClosedWatchServiceException e) {
        throw new BrokenDiffAwarenessException(
            "Internal error with the local file system watcher " + e);
      }
    }
    return newView(modifiedAbsolutePaths);
  }

  @Override
  public void close() {
    try {
      watchService.close();
    } catch (IOException ignored) {
      // Nothing we can do here.
    }
  }

  /** Returns the changed files caught by the watch service. */
  private Set<Path> collectChanges() throws BrokenDiffAwarenessException, IOException {
    Set<Path> createdFilesAndDirectories = new HashSet<>();
    Set<Path> deletedOrModifiedFilesAndDirectories = new HashSet<>();
    Set<Path> deletedTrackedDirectories = new HashSet<>();

    WatchKey watchKey;
    while ((watchKey = watchService.poll()) != null) {
      Path dir = watchKeyToDirBiMap.get(watchKey);
      Preconditions.checkArgument(dir != null);

      // We replay all the events for this watched directory in chronological order and
      // construct the diff of this directory since the last #collectChanges call.
      for (WatchEvent<?> event : watchKey.pollEvents()) {
        Kind<?> kind = event.kind();
        if (kind == StandardWatchEventKinds.OVERFLOW) {
          // TODO(bazel-team): find out when an overflow might happen, and maybe handle it more
          // gently.
          throw new BrokenDiffAwarenessException(
              "Overflow when watching local filesystem for " + "changes");
        }
        if (event.context() == null) {
          // The WatchService documentation mentions that WatchEvent#context may return null, but
          // doesn't explain how/why it would do so. Looking at the implementation, it only
          // happens on an overflow event. But we make no assumptions about that implementation
          // detail here.
          throw new BrokenDiffAwarenessException(
              "Insufficient information from local file system " + "watcher");
        }
        // For the events we've registered, the context given is a relative path.
        Path relativePath = (Path) event.context();
        Path path = dir.resolve(relativePath);
        Preconditions.checkState(path.isAbsolute(), path);
        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
          createdFilesAndDirectories.add(path);
          deletedOrModifiedFilesAndDirectories.remove(path);
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
          createdFilesAndDirectories.remove(path);
          deletedOrModifiedFilesAndDirectories.add(path);
          WatchKey deletedDirectoryKey = watchKeyToDirBiMap.inverse().get(path);
          if (deletedDirectoryKey != null) {
            // If the deleted directory has children, then there will also be events for the
            // WatchKey of the directory itself. WatchService#poll doesn't specify the order in
            // which WatchKeys are returned, so the key for the directory itself may be processed
            // *after* the current key (the parent of the deleted directory), and so we don't want
            // to remove the deleted directory from our bimap just yet.
            //
            // For example, suppose we have the file '/root/a/foo.txt' and are watching the
            // directories '/root' and '/root/a'. If the directory '/root/a' gets deleted then the
            // following is a valid sequence of events by key.
            //
            // WatchKey '/root/'
            // WatchEvent EVENT_MODIFY 'a'
            // WatchEvent EVENT_DELETE 'a'
            // WatchKey '/root/a'
            // WatchEvent EVENT_DELETE 'foo.txt'
            deletedTrackedDirectories.add(path);
            // Since inotify uses inodes under the covers we cancel our registration on this key to
            // avoid getting WatchEvents from a new directory that happens to have the same inode.
            deletedDirectoryKey.cancel();
          }
        } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
          // If a file was created and then modified, then the net diff is that it was
          // created.
          if (!createdFilesAndDirectories.contains(path)) {
            deletedOrModifiedFilesAndDirectories.add(path);
          }
        }
      }

      if (!watchKey.reset()) {
        // Watcher got deleted, directory no longer valid.
        watchKeyToDirBiMap.remove(watchKey);
      }
    }

    for (Path path : deletedTrackedDirectories) {
      WatchKey staleKey = watchKeyToDirBiMap.inverse().get(path);
      watchKeyToDirBiMap.remove(staleKey);
    }
    if (watchKeyToDirBiMap.isEmpty()) {
      // No more directories to watch, something happened the root directory being watched.
      throw new IOException("Root directory " + watchRootPath + " became inaccessible.");
    }

    Set<Path> changedPaths = new HashSet<>();
    for (Path path : createdFilesAndDirectories) {
      if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        // This is a new directory, so changes to it since its creation have not been watched.
        // We manually traverse the directory tree to register all the new subdirectories and find
        // all the new subdirectories and files.
        changedPaths.addAll(registerSubDirectoriesAndReturnContents(path));
      } else {
        changedPaths.add(path);
      }
    }
    changedPaths.addAll(deletedOrModifiedFilesAndDirectories);
    return changedPaths;
  }

  /**
   * Traverses directory tree to register subdirectories. Returns all paths traversed (as absolute
   * paths).
   */
  private Set<Path> registerSubDirectoriesAndReturnContents(Path rootDir) throws IOException {
    Set<Path> visitedAbsolutePaths = new HashSet<>();
    // Note that this does not follow symlinks.
    Files.walkFileTree(rootDir, new WatcherFileVisitor(visitedAbsolutePaths));
    return visitedAbsolutePaths;
  }

  /** File visitor used by Files.walkFileTree() upon traversing subdirectories. */
  private class WatcherFileVisitor extends SimpleFileVisitor<Path> {

    private final Set<Path> visitedAbsolutePaths;

    private WatcherFileVisitor(Set<Path> visitedPaths) {
      this.visitedAbsolutePaths = visitedPaths;
    }

    @Override
    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
      Preconditions.checkState(path.isAbsolute(), path);
      visitedAbsolutePaths.add(path);
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs)
        throws IOException {
      // It's important that we register the directory before we visit its children. This way we
      // are guaranteed to see new files/directories either on this #getDiff or the next one.
      // Otherwise, e.g., an intra-build creation of a child directory will be forever missed if it
      // happens before the directory is listed as part of the visitation.
      WatchKey key =
          path.register(
              watchService,
              StandardWatchEventKinds.ENTRY_CREATE,
              StandardWatchEventKinds.ENTRY_MODIFY,
              StandardWatchEventKinds.ENTRY_DELETE);
      Preconditions.checkState(path.isAbsolute(), path);
      visitedAbsolutePaths.add(path);
      watchKeyToDirBiMap.put(key, path);
      return FileVisitResult.CONTINUE;
    }
  }
}
