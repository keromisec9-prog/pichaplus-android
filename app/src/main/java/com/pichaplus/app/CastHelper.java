
    public void attach(android.view.View b) {
        b.setOnTouchListener((v, e) -> {
            if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                js("window.openCastChooser&&window.openCastChooser()");
            }
            return true;
        });
    }
}
