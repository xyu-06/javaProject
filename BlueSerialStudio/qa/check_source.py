"""Static regression checks for dynamically built Android UI, not a device test."""
from pathlib import Path
p=Path(__file__).resolve().parents[1] / 'app/src/main/java/com/embedded/blueserial/MainActivity.java'
s=p.read_text()
assert 'sendSuffixLabel.setText' in s, 'suffix hint not updated'
assert 'refreshSendSuffix();' in s and 'onItemSelected' in s, 'suffix options not synchronized'
assert 'WindowInsets.Type.ime()' in s, 'keyboard insets not handled'
assert 'page.addView(dock' in s, 'send panel not docked'
assert 'new AxisJoystickView(this, AxisJoystickView.Axis.VERTICAL)' in s
assert 'new AxisJoystickView(this, AxisJoystickView.Axis.HORIZONTAL)' in s
assert 'sendCombinedJoystick(released);' in s
assert 'macros.add(new MacroEntry' in s and 'macros.remove(existing)' in s
assert 'sliders.add(new SliderEntry' in s and 'sliders.remove(existing)' in s
assert 'saveMacros()' in s and 'saveSliders()' in s
assert 'putString(MACRO_STORE' in s and 'putString(SLIDER_STORE' in s
print('12 UI/source static checks passed')
