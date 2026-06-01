import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Dimensions, StyleSheet, View } from 'react-native';
import Animated, {
  Easing,
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

import { THEMES, type Theme, type ThemeName } from './theme';

interface Ctx {
  theme: Theme;
  /** Toggle theme with a circular "droplet" reveal originating at (x, y). */
  toggle: (x: number, y: number) => void;
}

const ThemeContext = createContext<Ctx>({ theme: THEMES.dark, toggle: () => {} });

export const useTheme = () => useContext(ThemeContext);

const { width: W, height: H } = Dimensions.get('window');
const MAX_R = Math.ceil(Math.hypot(W, H));

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [name, setName] = useState<ThemeName>('dark');
  const [overlayTheme, setOverlayTheme] = useState<Theme | null>(null);
  const animating = useRef(false);
  const origin = useRef({ x: W / 2, y: 60 });
  const progress = useSharedValue(0);

  // Called once the base theme has switched: drop the overlay on the *next*
  // frame so the underlying UI is already painted in the new theme — no flash.
  const clearOverlay = useCallback(() => {
    requestAnimationFrame(() => {
      setOverlayTheme(null);
      progress.value = 0;
      animating.current = false;
    });
  }, [progress]);

  // Called by the worklet when the circle finishes expanding.
  const onRevealed = useCallback(
    (next: ThemeName) => {
      // 1) switch the real theme while the full-screen circle still covers it
      setName(next);
      // 2) then remove the overlay next frame
      clearOverlay();
    },
    [clearOverlay]
  );

  const toggle = useCallback(
    (x: number, y: number) => {
      if (animating.current) return;
      animating.current = true;
      const next: ThemeName = name === 'dark' ? 'light' : 'dark';
      origin.current = { x, y };
      setOverlayTheme(THEMES[next]);
      progress.value = 0;
      progress.value = withTiming(
        1,
        { duration: 560, easing: Easing.out(Easing.cubic) },
        (done) => {
          if (done) runOnJS(onRevealed)(next);
        }
      );
    },
    [name, progress, onRevealed]
  );

  const circleStyle = useAnimatedStyle(() => ({
    transform: [{ scale: 0.0001 + progress.value }],
    opacity: progress.value > 0 ? 1 : 0,
  }));

  const value = useMemo<Ctx>(() => ({ theme: THEMES[name], toggle }), [name, toggle]);

  return (
    <ThemeContext.Provider value={value}>
      <View style={{ flex: 1, backgroundColor: THEMES[name].bg }}>
        {children}
        {overlayTheme && (
          <View pointerEvents="none" style={StyleSheet.absoluteFill}>
            <Animated.View
              style={[
                styles.circle,
                {
                  left: origin.current.x - MAX_R,
                  top: origin.current.y - MAX_R,
                  width: MAX_R * 2,
                  height: MAX_R * 2,
                  borderRadius: MAX_R,
                  backgroundColor: overlayTheme.bg,
                },
                circleStyle,
              ]}
            />
          </View>
        )}
      </View>
    </ThemeContext.Provider>
  );
}

const styles = StyleSheet.create({
  circle: { position: 'absolute' },
});
