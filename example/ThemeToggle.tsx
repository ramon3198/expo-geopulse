import { useRef } from 'react';
import { Pressable, Text, View } from 'react-native';

import { useTheme } from './ThemeProvider';

/** Round icon button that triggers the droplet theme reveal from its own center. */
export function ThemeToggle() {
  const { theme, toggle } = useTheme();
  const ref = useRef<View>(null);

  const onPress = () => {
    ref.current?.measureInWindow((x, y, w, h) => {
      toggle(x + w / 2, y + h / 2);
    });
  };

  const isDark = theme.name === 'dark';
  return (
    <View
      ref={ref}
      collapsable={false}
      style={{ borderRadius: 20, overflow: 'hidden' }}>
      <Pressable
        onPress={onPress}
        hitSlop={8}
        android_ripple={{ color: theme.ripple }}
        style={{
          width: 40,
          height: 40,
          borderRadius: 20,
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: theme.inner,
        }}>
        <Text style={{ fontSize: 17 }}>{isDark ? '🌙' : '☀️'}</Text>
      </Pressable>
    </View>
  );
}
