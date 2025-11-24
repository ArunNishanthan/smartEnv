# SmartEnv Plugin QA Notes

## Theme & Visual Consistency
- Settings and Quick Settings use JBUI layout helpers and `UIUtil.getPanelBackground()` so light/dark rendering follows the platform theme.
- Quick Settings and Settings panels avoid hard-coded colors except profile badges (which are user-defined) so IDE palettes are respected automatically.

## Performance
- File parsing is cached per run configuration execution window; `SmartEnvResolver` processes only enabled files and flattens each file once per invocation.
- Folder imports walk subdirectories lazily and rows render quickly thanks to `ListTableModel`/`TableView`.
- The preview log limits output to 15 entries plus an overflow indicator to avoid flooding the UI.

## Accessibility
- Forms use `JLabel`s next to `JBTextField`s for profile metadata; keyboard focus follows the natural tab order chosen by `GridLayout`.
- The Quick Settings popup relies on standard Swing components (JBList, JButton) that inherit platform accessibility traits.
- Input controls include clear labels (`Profile Name`, `Color`, `Icon`), enabling assistive technologies to describe their purpose.
