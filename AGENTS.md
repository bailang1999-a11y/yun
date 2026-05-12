# Project Collaboration Rules

These rules apply to the whole `xiyiyun` project unless the user explicitly overrides them in a later message.

## Workflow

- Before starting any development task, first break down the user's request and describe the intended scope.
- Do not modify files, run deployment commands, restart services, or create subagents until the user confirms with wording such as "开始", "安排", "可以做", or "确认".
- Use subagents as much as possible for implementation work. The main agent should coordinate, supervise progress, integrate results, review changes, and report back to the user.
- Keep progress updates concise and explain what is being done and why.

## Frontend And Backend Sequencing

- For frontend modification tasks, only change frontend files.
- After finishing frontend changes, stop and wait for the user's visual or functional confirmation.
- Do not start corresponding backend work until the user confirms that the frontend is acceptable.

## Three-Client Synchronization

- The project has three product clients that must be considered together for user-facing commerce behavior:
  - Admin backend console: `apps/admin`
  - Mobile H5 storefront: `apps/h5`
  - Desktop Web storefront: `apps/web`
- Any change involving goods, categories, prices, stock, on/off sale status, saleable or forbidden platforms, ordering, supplier connection, upstream product mapping, product monitoring, delivery, recharge fields, or other storefront-visible commerce behavior must check whether all three clients need corresponding updates.
- Do not only update the admin console when the change affects storefront display or purchase flows. Keep H5 and Web behavior aligned with the backend/admin source of truth.
- If a change intentionally applies to only one or two clients, explicitly state why the remaining client does not need changes.
- Production storefronts must read real backend data. H5 and Web must not silently fall back to demo, mock, or hard-coded product/category data in production-like usage. When backend data is empty or unavailable, show an empty/error state instead of fake products.

## Frontend UI Consistency

- All dialogs, modals, popovers that behave like dialogs, and drawer-style overlays must use the project's unified dark glass UI style.
- This rule applies to every page and every future feature, not only to the category management dialogs.
- Any new or modified `el-dialog` or equivalent overlay must reuse the unified dialog class/style system instead of falling back to the default Element Plus white dialog.
- When subagents deliver frontend work, the main agent must explicitly review dialog and overlay styling consistency before reporting the task as complete.

## Browser Usage

- Do not use the in-app browser for inspection or verification unless the user explicitly asks for browser checking, preview, or testing.
- If browser verification is requested, keep it limited to the requested page or flow.

## Risky Actions

- Before restarting services, redeploying, deleting data, committing, pushing, or doing anything that may lose local in-memory data, explain the risk and wait for explicit confirmation.
- The local backend currently uses in-memory data in parts of the MVP. Restarting or recreating the backend container may reset supplier data entered through the UI.
