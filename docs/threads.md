# Threads as channels

A thread is a named chat you come back to. It can also be a channel: point a scheduled task at it and every result arrives in that thread like a message. You read it there, reply in the thread, and the conversation carries on from the post.

## Example: a cybersecurity news thread

1. In the drawer, open **Threads** and tap **New thread**. Name it "Cybersecurity news".
2. Open the thread's menu and choose **Scheduled posts**, then **Add**. Describe the task ("Summarise today's security advisories that affect small teams"), set when it runs (for example every day at 8am) and save. **Deliver to** is already this thread.
3. Each time the task runs, the result appears in the thread and your phone gets a notification that opens it.
4. Reply in the thread ("Which of these affects us?") and the answer builds on the post.

You can also set this up from **Cron**: in **Deliver to**, pick the thread by its name. The list offers Beacon chat, each of your threads, and the other places Hermes can send results.

## Scheduled posts sheet

Each thread's **Scheduled posts** sheet lists the tasks that post into it, with their schedule and state. From there you can add a task for this thread, pause or resume it, run it now, or edit it. Editing a task keeps its target unless you change **Deliver to**.

## Good to know

- A post is saved in the thread's conversation, so it is there when you open the thread on another device and the next reply sees it, whether the thread was open or closed.
- If a run finishes while that thread is in the middle of answering you, the post waits and arrives right after that answer. A post is never added twice.
- If you delete a thread, tasks that point at it stop delivering and show "That thread is no longer available" until you change their target.
- Tasks that deliver to **Beacon chat** work as before.

## For the Hermes host

Under the hood the target is `deliver: phone:<thread session id>`, the explicit `platform:chat_id` form of Hermes cron. The plugin checks that the thread belongs to the signed-in owner and profile, records the result with Hermes' own session store, and queues the notification. See the plugin [README](../hermes-plugin/beacon/README.md#scheduled-posts-in-threads).
