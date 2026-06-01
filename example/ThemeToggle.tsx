import { useRef } from 'react';
import { Pressable, Text, View } from 'react-native';

import { useTheme } from './ThemeProvider';

/** Sun/Moon button that triggers the droplet theme reveal from its own center. */
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
    <View ref={ref} collapsable={false}>
      <Pressable
        onPress={onPress}
        hitSlop={8}
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          gap: 6,
          height: 36,
          paddingHorizontal: 12,
          borderRadius: 18,
          backgroundColor: theme.toolBg,
          borderWidth: 1,
          borderColor: theme.accent + '55',
        }}>
        <Text style={{ fontSize: 15 }}>{isDark ? '🌙' : '☀️'}</Text>
        <Text style={{ color: theme.toolText, fontWeight: '700', fontSize: 12.5 }}>
          {isDark ? 'Dark' : 'Light'}
        </Text>
      </Pressable>
    </View>
  );
}
