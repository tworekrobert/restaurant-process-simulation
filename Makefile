.PHONY: build run run-normal run-rush run-limited run-no-restock run-short clean

JAVA ?= java
JAVAC ?= javac
OUT_DIR ?= out

build:
	$(JAVAC) -encoding UTF-8 -d $(OUT_DIR) src/*.java

run: build
	$(JAVA) -cp $(OUT_DIR) RestaurantLauncher \
		--mode NORMAL

run-normal: build
	$(JAVA) -cp $(OUT_DIR) RestaurantLauncher --mode NORMAL

run-rush: build
	$(JAVA) -cp $(OUT_DIR) RestaurantLauncher --mode RUSH_HOUR

run-limited: build
	$(JAVA) -cp $(OUT_DIR) RestaurantLauncher --mode LIMITED_RESOURCES

run-no-restock: build
	$(JAVA) -cp $(OUT_DIR) RestaurantLauncher --mode NO_RESTOCK

run-short: build
	$(JAVA) -cp $(OUT_DIR) RestaurantLauncher --mode SHORT_DEMO

clean:
	rm -rf $(OUT_DIR)
