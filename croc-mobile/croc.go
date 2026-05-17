package croc

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"sync"

	"github.com/schollz/croc/v10/src/cli"
	"github.com/schollz/croc/v10/src/utils"
)

var (
	mu          sync.Mutex
	wg          sync.WaitGroup
	runResult   error
	pipeReader  *os.File
	pipeWriter  *os.File
	origStderr  *os.File
	panicResult interface{}
)

type crocConfig struct {
	Args    []string          `json:"args"`
	Env     map[string]string `json:"env"`
	WorkDir string            `json:"workDir"`
}

func Start(configJson string) (int, error) {
	mu.Lock()
	defer mu.Unlock()

	var cfg crocConfig
	if err := json.Unmarshal([]byte(configJson), &cfg); err != nil {
		return -1, fmt.Errorf("croc: failed to parse config JSON: %w", err)
	}

	os.Args = cfg.Args
	for k, v := range cfg.Env {
		os.Setenv(k, v)
	}
	if cfg.WorkDir != "" {
		if err := os.Chdir(cfg.WorkDir); err != nil {
			return -1, fmt.Errorf("croc: chdir failed: %w", err)
		}
	}

	r, w, err := os.Pipe()
	if err != nil {
		return -1, fmt.Errorf("croc: pipe failed: %w", err)
	}

	origStderr = os.Stderr
	os.Stderr = w
	pipeReader = r
	pipeWriter = w

	runResult = nil
	panicResult = nil

	wg.Add(1)
	go func() {
		defer wg.Done()
		defer func() {
			if r := recover(); r != nil {
				log.Printf("croc: recovered panic in cli.Run: %v", r)
				panicResult = r
				runResult = fmt.Errorf("panic: %v", r)
			}
		}()
		runResult = cli.Run()
		w.Close()
	}()

	return int(r.Fd()), nil
}

func WaitDone() (int, error) {
	wg.Wait()

	mu.Lock()
	defer mu.Unlock()

	if origStderr != nil {
		os.Stderr = origStderr
		origStderr = nil
	}
	if pipeReader != nil {
		pipeReader.Close()
		pipeReader = nil
	}
	if pipeWriter != nil {
		pipeWriter.Close()
		pipeWriter = nil
	}

	utils.RemoveMarkedFiles()

	if panicResult != nil {
		log.Printf("croc: cli.Run panicked: %v", panicResult)
		return 2, fmt.Errorf("panic: %v", panicResult)
	}
	if runResult != nil {
		log.Printf("croc: cli.Run returned error: %v", runResult)
		return 1, runResult
	}
	return 0, nil
}

func Cancel() {
	mu.Lock()
	defer mu.Unlock()
	if pipeWriter != nil {
		pipeWriter.Close()
		pipeWriter = nil
	}
	if origStderr != nil {
		os.Stderr = origStderr
		origStderr = nil
	}
}
